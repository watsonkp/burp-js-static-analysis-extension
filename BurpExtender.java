package burp;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.io.FileReader;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

public class BurpExtender implements IBurpExtender, ITab, IContextMenuFactory, ActionListener {
	private IBurpExtenderCallbacks callbacks;
	private IExtensionHelpers helpers;

	private static PrintWriter stdout;
	private static PrintWriter stderr;

	private JPanel panel;

	private byte[] script;
	private JTextArea source;
	private JFileChooser libraryChooser;
	private JTextArea command;
	private JTextArea output;

	IHttpRequestResponse[] messages;

	private ScriptEngineManager engineManager;
	private ScriptEngine engine;
	Invocable invocable;

	private JTextField libraryPath;

	private void jsInit(String engineName) {
		engineManager = new ScriptEngineManager();
		engine = engineManager.getEngineByName(engineName);
		if (engine == null) {
			System.out.println("Could not find nashorn engine");
			return;
		}

		invocable = (Invocable) engine;
	}

	public void jsEval(String payload) {
		StringWriter sw = new StringWriter();
		ScriptContext context = engine.getContext();
		context.setWriter(sw);
		context.setErrorWriter(sw);
		try {
			Object ret = engine.eval(payload);
			if (ret == null) {
				output.setText(sw + "\nret=null");
			} else {
				output.setText(sw + "\n" + ret.toString());
			}
		} catch (ScriptException e) {
			//System.out.println(e);
			output.setText(e.getMessage());
		}
	}

	public void jsLoadFile(String path) {
		try {
			FileReader reader = new FileReader(path);
			engine.eval(reader);
		} catch (FileNotFoundException e) {
			stderr.println(e);
		} catch (ScriptException e) {
			stderr.println(e);
		}
	}

	private void jsBindScript(String script) {
		engine.getContext().setAttribute("program", script, ScriptContext.ENGINE_SCOPE);
	}

	@Override
	public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks) {
		this.callbacks = callbacks;
		this.helpers = callbacks.getHelpers();

		stdout = new PrintWriter(callbacks.getStdout(), true);
		stderr = new PrintWriter(callbacks.getStderr(), true);

		callbacks.setExtensionName("JS Analysis");

		callbacks.registerContextMenuFactory(this);

		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				panel = new JPanel();

				source = new JTextArea(15, 100);
				JScrollPane sourceScroller = new JScrollPane(source);

				libraryPath = new JTextField(100);
				JButton pathButton = new JButton("Use library");
				pathButton.addActionListener(e -> {
						//command.setText("ran");
						//jsEval("1+1");
						//jsEval(command.getText());
						JFileChooser libraryChooser = new JFileChooser();
						FileNameExtensionFilter filter = new FileNameExtensionFilter("JavaScript", "js");
						libraryChooser.setFileFilter(filter);
						int returnVal = libraryChooser.showOpenDialog(panel);
						if (returnVal == JFileChooser.APPROVE_OPTION) {
							libraryPath.setText(libraryChooser.getSelectedFile().getAbsolutePath());
							jsLoadFile(libraryPath.getText());
						}
					});

				command = new JTextArea(15, 100);
				JScrollPane commandScroller = new JScrollPane(command);

				JButton loadButton = new JButton("Load");
				loadButton.addActionListener(e -> {
					JFileChooser chooser = new JFileChooser();
					FileNameExtensionFilter filter = new FileNameExtensionFilter("JavaScript", "js");
					chooser.setFileFilter(filter);
					int returnVal = chooser.showOpenDialog(panel);
					if (returnVal != JFileChooser.APPROVE_OPTION) {
						return;
					}
					String path = chooser.getSelectedFile().getAbsolutePath();
					try {
						byte[] content = Files.readAllBytes(Paths.get(path));
						command.setText(new String(content, "utf-8"));
					} catch (IOException eio) {
						stderr.println(eio);
					}
				});

				// TODO: save button

				JButton runButton = new JButton("Run");
				runButton.addActionListener(e -> {
						jsEval(command.getText());
					});

				output = new JTextArea(15, 100);
				JScrollPane outputScroller = new JScrollPane(output);

				panel.add(sourceScroller);
				//panel.add(libraryChooser);
				panel.add(libraryPath);
				panel.add(pathButton);
				panel.add(commandScroller);
				panel.add(loadButton);
				panel.add(runButton);
				panel.add(outputScroller);

				source.setText("JavaScript source code...");
				command.setText("Command...");
				output.setText("Command output...");


				callbacks.customizeUiComponent(panel);
				callbacks.addSuiteTab(BurpExtender.this);
			}
		});

		jsInit("nashorn");
	}

	@Override
	public String getTabCaption() {
		return "JS Analysis";
	}

	@Override
	public Component getUiComponent() {
		return panel;
	}

	@Override
	public List<JMenuItem> createMenuItems(IContextMenuInvocation invocation) {
		byte context = invocation.getInvocationContext();
		if (context != IContextMenuInvocation.CONTEXT_MESSAGE_EDITOR_RESPONSE && context != IContextMenuInvocation.CONTEXT_MESSAGE_VIEWER_RESPONSE) {
			return null;
		}

		List<JMenuItem> menuItems = new ArrayList<JMenuItem>();
		JMenuItem send = new JMenuItem("Send to JS Analysis");
		send.setActionCommand("SEND_RESPONSE");
		send.addActionListener(this);

		messages = invocation.getSelectedMessages();

		menuItems.add(send);

		return menuItems;
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		String command = e.getActionCommand();
		if (command != "SEND_RESPONSE") {
			messages = null;
			return;
		}

		byte[] response = messages[0].getResponse();
		IResponseInfo info = helpers.analyzeResponse(response);
		int offset = info.getBodyOffset();
		script = Arrays.copyOfRange(response, offset, response.length);
		//source.setText("Got response from context menu");
		source.setText(new String(script));
		jsBindScript(source.getText());
	}

}
