package avi.copy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class Main {

	private static final class Copier extends Thread {

		private static boolean DEBUG = false;

		private static final int S_ABORTED = 0;

		private static final int S_COPYING = 1;

		private static final int S_PAUSED = 2;

		private volatile long bytesCopied;

		private final File destination;

		private final long length;

		private final File source;

		private volatile int state;

		private String trouble;

		public Copier(File source, File destination) {
			super("copy");
			this.bytesCopied = 0;
			this.destination = destination;
			this.length = source.length();
			this.source = source;
			this.state = S_COPYING;
			this.trouble = null;
		}

		public synchronized void abort() {
			state = S_ABORTED;
			notifyAll();
		}

		public synchronized long bytesCopied() {
			return bytesCopied;
		}

		public synchronized boolean done() {
			return bytesCopied == length;
		}

		public synchronized void pause() {
			if (state != S_ABORTED) {
				state = S_PAUSED;
				notifyAll();
			}
		}

		@Override
		public void run() {
			if (destination.exists()) {
				trouble = "Destination file exists.";
				return;
			}

			FileChannel out = null;

			try {
				bytesCopied = 0;
				trouble = null;

				if (DEBUG) {
					try {
						Thread.sleep(500);
					} catch (InterruptedException e) {
						// ignore
					}
					return;
				}

				destination.getParentFile().mkdirs();

				FileReader in = new FileReader(source.toPath());

				out = FileChannel.open(destination.toPath(),
						StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				out.truncate(length);

				for (;;) {
					if (waitUnpaused() == S_ABORTED) {
						in.abort();
						break;
					}

					ByteBuffer buffer = in.read();

					if (buffer == null) {
						break;
					}

					if (waitUnpaused() == S_ABORTED) {
						in.abort();
						break;
					}

					long bytesRead = buffer.remaining();

					out.write(buffer);
					bytesCopied += bytesRead;
				}
			} catch (IOException e) {
				trouble = e.getLocalizedMessage();
			} finally {
				if (out != null) {
					if (state == S_ABORTED) {
						try {
							out.truncate(0);
						} catch (IOException e) {
							// ignore - we're about to delete the file
						}
					}

					try {
						out.close();
					} catch (IOException e) {
						trouble = e.getLocalizedMessage();
					}
				}

				bytesCopied = length;

				if (state != S_ABORTED) {
					destination.setLastModified(source.lastModified());
				} else {
					if (destination.exists()) {
						destination.delete();
					}
					if (trouble == null) {
						trouble = "aborted";
					}
				}
			}
		}

		public synchronized void unpause() {
			if (state == S_PAUSED) {
				state = S_COPYING;
				notifyAll();
			}
		}

		private synchronized int waitUnpaused() {
			while (state == S_PAUSED) {
				try {
					wait();
				} catch (InterruptedException e) {
					// ignore
				}
			}

			return state;
		}
	}

	private static final class FileReader extends Thread {

		private static ByteBuffer[] makeBuffers(int count, int bufferSize) {
			ByteBuffer[] buffers = new ByteBuffer[count];

			for (int i = 0; i < count; ++i) {
				buffers[i] = ByteBuffer.allocateDirect(bufferSize);
			}

			return buffers;
		}

		private ByteBuffer buffer;

		private boolean done;

		private final Path source;

		private IOException trouble;

		public FileReader(Path source) {
			super("read");
			this.source = source;

			setDaemon(true);
			start();
		}

		public synchronized void abort() {
			done = true;
			notifyAll();
		}

		public synchronized ByteBuffer read() throws IOException {
			for (;;) {
				if (trouble != null) {
					throw trouble;
				}

				if (done) {
					return null;
				}

				ByteBuffer result = buffer;

				if (result != null) {
					// advise the reading thread to switch buffers
					buffer = null;
					notifyAll();
					return result;
				}

				try {
					wait();
				} catch (InterruptedException e) {
					// ignore
				}
			}
		}

		@Override
		public void run() {
			try (ReadableByteChannel channel = FileChannel.open(source, StandardOpenOption.READ)) {
				ByteBuffer[] buffers = makeBuffers(2, 1024 * 1024);

				run: for (int i = 0;; i ^= 1) {
					// wait for peer to consume buffer
					synchronized (this) {
						for (;;) {
							if (done) {
								break run;
							}

							if (buffer == null) {
								break;
							}

							try {
								wait();
							} catch (InterruptedException e) {
								// ignore
							}
						}
					}

					ByteBuffer current = buffers[i];

					current.clear();

					int byteCount = channel.read(current);

					synchronized (this) {
						if (byteCount >= 0) {
							current.flip();
							buffer = current;
						} else {
							done = true;
						}

						notifyAll();
					}
				}
			} catch (IOException e) {
				trouble = e;
				notifyAll();
			}
		}
	}

	private static final class WorkItem implements Comparable<WorkItem> {

		private final long length;

		private final long modified;

		private final String pathName;

		public WorkItem(String pathName, File file) {
			super();
			this.length = file.length();
			this.modified = file.lastModified();
			this.pathName = pathName;
		}

		@Override
		public int compareTo(WorkItem that) {
			return this.pathName.compareTo(that.pathName);
		}

		public long getLength() {
			return length;
		}

		public long getModified() {
			return modified;
		}

		public String getPathName() {
			return pathName;
		}

		@Override
		public String toString() {
			return String.format("%1$s (%2$,d bytes)", // <br/>
					pathName, Long.valueOf(length));
		}
	}

	private static final DateFormat DATE = new SimpleDateFormat("yyyyMMddHHmm");

	private static final Pattern Extensions = Pattern.compile(".*\\.(avi|mp4|mpg)", Pattern.CASE_INSENSITIVE);

	private static final Pattern Mnemonics = Pattern.compile("&");

	private static String hideMnemonic(String label) {
		return Mnemonics.matcher(label).replaceAll("&&");
	}

	public static void main(String[] args) {
		new Main().run(args);
	}

	private static Button newButton(Composite parent, String text) {
		Button button;

		button = new Button(parent, SWT.PUSH);
		button.setLayoutData(new GridData(SWT.LEAD, SWT.UP, false, false));
		button.setText(text);

		return button;
	}

	private static Label newLabel(Composite parent, String text) {
		Label label;

		label = new Label(parent, SWT.LEAD);
		label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
		label.setText(text);

		return label;
	}

	private static ProgressBar newProgressBar(Composite parent) {
		ProgressBar bar;

		bar = new ProgressBar(parent, SWT.HORIZONTAL + SWT.SMOOTH);
		bar.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		return bar;
	}

	private static SelectionAdapter newSelectionAdapter(Runnable action) {
		return new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent event) {
				action.run();
			}
		};
	}

	private static Text newText(Composite parent) {
		Text field;

		field = new Text(parent, SWT.BORDER + SWT.LEAD);
		field.setLayoutData(new GridData(SWT.FILL, SWT.UP, true, false));

		return field;
	}

	private static void rescaleProgressBar(Event event) {
		ProgressBar bar = (ProgressBar) event.widget;
		Point size = bar.getSize();

		bar.setMinimum(0);
		bar.setMaximum(size.x);
	}

	private Document controlData;

	private Text controlFile;

	private Copier copier;

	private Button copyButton;

	private Text destinationFolder;

	private boolean dirty;

	private Button exitButton;

	private final Map<String, Date> newest;

	private Button pauseButton;

	private boolean paused;

	private ProgressBar progressBar;

	private Composite progressGroup;

	private Shell shell;

	private Button skipButton;

	private Text sourceFolder;

	private Label statusLabel;

	private int workIndex;

	private final List<WorkItem> workToDo;

	private Main() {
		super();
		this.dirty = false;
		this.newest = new TreeMap<>();
		this.workToDo = new ArrayList<>();
	}

	private void armUpdater() {
		shell.getDisplay().timerExec(100, () -> updateUI());
	}

	private String computeWork() {
		File srcDir = new File(sourceFolder.getText());

		if (!srcDir.exists() || !srcDir.isDirectory()) {
			return "Source folder not found.";
		}

		File ctlFile = new File(controlFile.getText());

		if (!ctlFile.exists() || ctlFile.isDirectory()) {
			return "Control file not found.";
		}

		File dstDir = new File(destinationFolder.getText());

		if (!dstDir.exists() || !dstDir.isDirectory()) {
			return "Destination folder not found.";
		}

		try {
			readControlData(ctlFile);
		} catch (IOException e) {
			return "Can't read control file: " + e.getMessage();
		}

		dirty = false;
		workToDo.clear();

		for (Entry<String, Date> entry : newest.entrySet()) {
			String folderName = entry.getKey();
			Date time = entry.getValue();
			File srcFolder = new File(srcDir, folderName);
			File dstFolder = new File(dstDir, folderName);

			String[] srcList = srcFolder.list();

			if (srcList == null) {
				continue;
			}

			for (String name : srcList) {
				if (!Extensions.matcher(name).matches()) {
					continue;
				}

				if (new File(dstFolder, name).exists()) {
					continue;
				}

				File file = new File(srcFolder, name);
				Date modified = new Date(file.lastModified());

				if (modified.after(time)) {
					String pathName = folderName + '/' + name;

					workToDo.add(new WorkItem(pathName, file));
				}
			}
		}

		Collections.sort(workToDo);

		return null;
	}

	private void copyPressed() {
		boolean wasPaused = paused;

		copyButton.setEnabled(false);
		exitButton.setEnabled(false);
		pauseButton.setEnabled(true);
		paused = false;
		progressGroup.setVisible(true);
		skipButton.setEnabled(true);

		if (copier != null) {
			copier.unpause();
		} else {
			if (!wasPaused) {
				workIndex = 0;
			}

			startNextFile();
			armUpdater();
		}
	}

	private void createControls(Composite parent) {
		parent.setLayout(new GridLayout());

		{
			Group group;

			group = new Group(parent, SWT.NONE);
			group.setLayout(new GridLayout(2, false));
			group.setLayoutData(new GridData(SWT.FILL, SWT.UP, true, false));
			group.setText("Configuration");

			ModifyListener settingsListener = event -> updateStatus();

			newLabel(group, "Source:");
			sourceFolder = newText(group);
			sourceFolder.addModifyListener(settingsListener);

			newLabel(group, "Control file:");
			controlFile = newText(group);
			controlFile.addModifyListener(settingsListener);

			newLabel(group, "Destination:");
			destinationFolder = newText(group);
			destinationFolder.addModifyListener(settingsListener);
		}

		{
			Composite buttons;

			buttons = new Composite(parent, SWT.NONE);
			buttons.setLayout(new GridLayout(4, false));
			buttons.setLayoutData(new GridData(SWT.CENTER, SWT.UP, false, false));

			copyButton = newButton(buttons, "Copy");
			copyButton.addSelectionListener(newSelectionAdapter(() -> copyPressed()));
			copyButton.setEnabled(false);

			pauseButton = newButton(buttons, "Pause");
			pauseButton.addSelectionListener(newSelectionAdapter(() -> pausePressed()));
			pauseButton.setEnabled(false);

			skipButton = newButton(buttons, "Skip file");
			skipButton.addSelectionListener(newSelectionAdapter(() -> skipPressed()));
			skipButton.setEnabled(false);

			exitButton = newButton(buttons, "Exit");
			exitButton.addSelectionListener(newSelectionAdapter(() -> shell.dispose()));
			exitButton.setEnabled(true);
		}

		{
			GridData gridData;
			Composite group;

			gridData = new GridData(SWT.FILL, SWT.FILL, true, true);
			gridData.minimumWidth = 600;

			group = new Composite(parent, SWT.NONE);
			group.setLayout(new GridLayout());
			group.setLayoutData(gridData);
			group.setVisible(false);

			progressBar = newProgressBar(group);
			progressBar.addListener(SWT.Resize, event -> rescaleProgressBar(event));

			progressGroup = group;
		}

		{
			Composite group = new Composite(parent, SWT.NONE);

			group.setLayout(new GridLayout());
			group.setLayoutData(new GridData(SWT.FILL, SWT.UP, true, false));

			statusLabel = new Label(group, SWT.LEAD);
			statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.UP, true, false));
		}
	}

	private void handleDispose() {
		if (copier != null) {
			copier.abort();
		}
	}

	private void pausePressed() {
		copier.pause();
		copyButton.setEnabled(true);
		exitButton.setEnabled(false);
		pauseButton.setEnabled(false);
		paused = true;
		skipButton.setEnabled(true);
	}

	private void readControlData(File file) throws IOException {
		controlData = null;

		try (InputStream in = new FileInputStream(file)) {
			controlData = DocumentBuilderFactory // <br/>
					.newInstance() // <br/>
					.newDocumentBuilder() // <br/>
					.parse(in);
		} catch (ParserConfigurationException | SAXException e) {
			throw new IOException(e);
		}

		if (controlData == null) {
			return;
		}

		newest.clear();

		NodeList children = controlData.getDocumentElement().getChildNodes();

		for (int i = 0, n = children.getLength(); i < n; ++i) {
			Node node = children.item(i);

			if (!(node instanceof Element)) {
				continue;
			}

			Date date;
			Element element = (Element) node;
			Attr time;
			Attr title;

			if (!"video".equals(element.getTagName())) {
				continue;
			}

			if ((title = element.getAttributeNode("title")) == null) {
				continue;
			}

			if ((time = element.getAttributeNode("time")) == null) {
				date = new Date(0);
			} else {
				try {
					date = DATE.parse(time.getValue());
				} catch (ParseException e) {
					date = new Date(0);
				}
			}

			newest.put(title.getValue(), date);
		}
	}

	private void run(String[] args) {
		Display display = new Display();

		shell = new Shell(display);

		createControls(shell);

		controlFile.setText(args.length > 0 ? args[0] : "D:/video/Meghan.xml");
		destinationFolder.setText(args.length > 1 ? args[1] : "F:/video");
		sourceFolder.setText(args.length > 2 ? args[2] : "D:/video");

		updateStatus();

		shell.addDisposeListener(event -> handleDispose());
		shell.setText("Video Copier");
		shell.pack();
		shell.setMinimumSize(shell.getSize());
		shell.open();

		while (!shell.isDisposed()) {
			if (!display.readAndDispatch()) {
				display.sleep();
			}
		}

		display.dispose();
		handleDispose();
	}

	private void saveControlData(File file) throws IOException {
		NodeList children = controlData.getDocumentElement().getChildNodes();

		for (int i = 0, n = children.getLength(); i < n; ++i) {
			Node node = children.item(i);

			if (!(node instanceof Element)) {
				continue;
			}

			Date date;
			Element element = (Element) node;
			Attr time;
			String timeValue;
			Attr title;

			if (!"video".equals(element.getTagName())) {
				continue;
			}

			if ((title = element.getAttributeNode("title")) == null) {
				continue;
			}

			if ((date = newest.get(title.getValue())) == null) {
				continue;
			}

			timeValue = DATE.format(date);

			if ((time = element.getAttributeNode("time")) != null) {
				time.setValue(timeValue);
			} else {
				element.setAttribute("time", timeValue);
			}
		}

		try (FileWriter writer = new FileWriter(file)) {
			Transformer transformer = TransformerFactory.newInstance().newTransformer();

			transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			transformer.setOutputProperty(OutputKeys.METHOD, "xml");
			transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");

			DOMSource source = new DOMSource(controlData);
			StreamResult target = new StreamResult(writer);

			transformer.transform(source, target);
		} catch (TransformerException e) {
			throw new IOException(e);
		}
	}

	private void skipPressed() {
		if (copier != null) {
			copier.abort();
		}
	}

	private void startNextFile() {
		if (workIndex < workToDo.size()) {
			WorkItem item = workToDo.get(workIndex);
			String pathName = item.getPathName();
			File source = new File(sourceFolder.getText(), pathName);
			File destination = new File(destinationFolder.getText(), pathName);

			copier = new Copier(source, destination);

			if (paused) {
				copier.pause();
			}

			copier.start();
		} else {
			copier = null;
			copyButton.setEnabled(true);
			exitButton.setEnabled(true);
			pauseButton.setEnabled(false);
			skipButton.setEnabled(false);
		}
	}

	private void updateNewest(WorkItem item) {
		String pathName = item.getPathName();
		int slash = pathName.lastIndexOf('/');

		if (slash >= 0) {
			String folderName = pathName.substring(0, slash);
			Date current = newest.get(folderName);
			Date date = new Date(item.getModified());

			if (current == null || date.after(current)) {
				dirty = true;
				newest.put(folderName, date);
			}
		}
	}

	private void updateStatus() {
		String error = computeWork();
		int fileCount = workToDo.size();
		String status;

		if (error != null) {
			copyButton.setEnabled(false);
			status = error;
		} else if (fileCount == 0) {
			copyButton.setEnabled(false);
			status = "Destination is up-to-date.";
		} else {
			long totalBytes = 0;

			for (WorkItem workItem : workToDo) {
				totalBytes += workItem.getLength();
			}

			status = String.format("%3$,d %4$s in %1$d %2$s to be copied.", // <br/>
					Integer.valueOf(fileCount), // <br/>
					(fileCount == 1 ? "file" : "files"), // <br/>
					Long.valueOf(totalBytes), // <br/>
					(totalBytes == 1 ? "byte" : "bytes"));

			copyButton.setEnabled(true);
		}

		statusLabel.setText(status);
	}

	private void updateUI() {
		if (shell.isDisposed()) {
			return;
		}

		if (copier != null && copier.done()) {
			updateNewest(workToDo.get(workIndex));
			copier = null;
			workIndex += 1;

			if (!paused) {
				startNextFile();
			}
		}

		if (copier == null) {
			copyButton.setEnabled(true);
			exitButton.setEnabled(true);
			pauseButton.setEnabled(false);
			progressGroup.setVisible(paused);
			skipButton.setEnabled(false);

			if (dirty && !paused) {
				File ctlFile = new File(controlFile.getText());

				try {
					saveControlData(ctlFile);
					dirty = false;
					updateStatus();
				} catch (IOException e) {
					statusLabel.setText("Can't save control file: "
							+ hideMnemonic(e.getLocalizedMessage()));
				}
			}

			return;
		}

		long bytesCopied = 0;
		int fileCount = workToDo.size();
		long totalBytes = 0;

		for (int i = 0; i < fileCount; ++i) {
			WorkItem item = workToDo.get(i);
			long length = item.getLength();

			if (i < workIndex) {
				bytesCopied += length;
			} else if (i == workIndex) {
				bytesCopied += copier.bytesCopied();
				statusLabel.setText("Copying " + hideMnemonic(item.toString()));
			}

			totalBytes += length;
		}

		if (totalBytes <= 0) {
			totalBytes = 1;
		}

		int max = progressBar.getMaximum();
		int current = (int) ((bytesCopied / (double) totalBytes) * max);

		progressBar.setSelection(current);
		armUpdater();
	}

}
