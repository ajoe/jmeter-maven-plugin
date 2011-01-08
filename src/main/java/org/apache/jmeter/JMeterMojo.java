package org.apache.jmeter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.Thread.UncaughtExceptionHandler;
import java.security.Permission;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.tools.ant.DirectoryScanner;

/**
 * JMeter Maven plugin.
 * 
 * @author Tim McCune, Lean (mateamargo), Ron Alleva, Gerd Aschemann, Jochen
 *         Stiepel
 * @goal jmeter
 * @requiresDependencyResolution test
 */
public class JMeterMojo extends AbstractMojo {

	private static final Pattern PAT_ERROR = Pattern.compile(".*\\s+ERROR\\s+.*");

	/**
	 * @parameter
	 */
	private List<String> includes;

	/**
	 * @parameter
	 */
	private List<String> excludes;

	/**
	 * JMeter Test Plan Dir
	 * 
	 * @parameter expression="${basedir}/src/test/jmeter"
	 */
	private File srcDir;

	/**
	 * JMeter Reports
	 * 
	 * @parameter expression="jmeter-reports"
	 */
	private File reportDir;

	/**
	 * JMeter Properties from jmeter.properties
	 * 
	 * @parameter expression="${basedir}/src/test/jmeter/jmeter.properties"
	 */
	private File jmeterProps;

	/**
	 * JMeter Properties to be overridden
	 * 
	 * @parameter
	 */
	private Map jmeterUserProperties;

	/**
	 * @parameter
	 */
	private boolean remote;

	/**
	 * The dir where the compiled classes are placed
	 * 
	 * @parameter expression="${jmeter.compiledClasses}"
	 *            default-value="${basedir}/target/classes"
	 */
	private File compiledClasses;

	/**
	 * The dir where the compiled test classes are placed
	 * 
	 * @parameter expression="${jmeter.compiledTestClasses}"
	 *            default-value="${basedir}/target/test-classes"
	 */
	private File compiledTestClasses;

	/**
	 * The file where the classpath is written
	 * 
	 * @parameter expression="${jmeter.classpathDump}"
	 *            default-value="${basedir}/target/classpath"
	 */
	private File classpathDump;

	/**
	 * securityManagerDisabled = true allows you to disable the System.exit()
	 * Calls from within JMeter. On the other hand it has a negativ impact if
	 * you need the current SecurityManager, e.g. to connect to a JBoss server.
	 * 
	 * securtiyManagerDisabled = false no thanges to the SecurityManager, but I
	 * can happen, that JMeter calls System.exit() and kills the current running
	 * JVM with that - so the running Maven process.
	 * 
	 * @parameter default-value="false"
	 */
	private boolean securityManagerDisabled;

	private String jmeterWorkDir = "target" + File.separator + "jmeter";
	private File workDir;
	private File saveServiceProps;
	private File upgradeProps;
	private File jmeterLog;
	private DateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss");

	/**
	 * Run all JMeter tests.
	 */
	public void execute() throws MojoExecutionException, MojoFailureException {
		addDependenciesToClasspath();
		initSystemProps();

		// try {
		DirectoryScanner scanner = new DirectoryScanner();
		scanner.setBasedir(srcDir);
		scanner.setIncludes(includes == null ? new String[] { "**/*.jmx" } : includes.toArray(new String[] {}));
		if (excludes != null) {
			scanner.setExcludes(excludes.toArray(new String[] {}));
		}
		scanner.scan();
		for (String file : scanner.getIncludedFiles()) {
			executeTest(new File(srcDir, file));
		}
		checkForErrors();
		// } finally {
		// These files are JMeter version specific. Do not delete to show
		// which files haven been used.
		// saveServiceProps.delete();
		// upgradeProps.delete();
		// }
	}

	private void checkForErrors() throws MojoExecutionException, MojoFailureException {
		try {
			BufferedReader in = new BufferedReader(new FileReader(jmeterLog));
			String line;
			while ((line = in.readLine()) != null) {
				if (PAT_ERROR.matcher(line).find()) {
					throw new MojoFailureException("There were test errors, see logfile '" + jmeterLog
							+ "' for further information");
				}
			}
			in.close();
		} catch (IOException e) {
			throw new MojoExecutionException("Can't read log file", e);
		}
	}

	private void initSystemProps() throws MojoExecutionException {
		workDir = new File(jmeterWorkDir);
		workDir.mkdirs();
		copyPropertyFiles();
		jmeterLog = new File(workDir, "jmeter.log");
		try {
			System.setProperty("log_file", jmeterLog.getCanonicalPath());
		} catch (IOException e) {
			throw new MojoExecutionException("Can't get canonical path for log file", e);
		}
	}

	/**
	 * This mess is necessary because JMeter must load this info from a file. Do
	 * it for saveservice.properties and upgrade.properties Resources won't
	 * work.
	 */
	private void copyPropertyFiles() throws MojoExecutionException {
		saveServiceProps = new File(workDir, "saveservice.properties");
		upgradeProps = new File(workDir, "upgrade.properties");
		try {
			FileWriter out = new FileWriter(saveServiceProps);
			IOUtils.copy(Thread.currentThread().getContextClassLoader().getResourceAsStream("saveservice.properties"),
					out);
			out.flush();
			out.close();
			System.setProperty("saveservice_properties", File.separator + jmeterWorkDir + File.separator
					+ "saveservice.properties");

			out = new FileWriter(upgradeProps);
			IOUtils.copy(Thread.currentThread().getContextClassLoader().getResourceAsStream("upgrade.properties"), out);
			out.flush();
			out.close();
			System.setProperty("upgrade_properties", File.separator + jmeterWorkDir + File.separator
					+ "upgrade.properties");

		} catch (IOException e) {
			throw new MojoExecutionException("Could not create temporary saveservice.properties", e);
		}
	}

	/**
	 * Executes a single JMeter test by building up a list of command line
	 * parameters to pass to JMeter.start().
	 */
	private void executeTest(File test) throws MojoExecutionException {
		try {
			getLog().info("Executing test: " + test.getCanonicalPath());
			String reportFileName = test.getName().substring(0, test.getName().lastIndexOf(".")) + "-"
					+ fmt.format(new Date()) + ".xml";

			List<String> argsTmp = Arrays.asList("-n", "-t", test.getCanonicalPath(), "-l", reportDir.toString()
					+ File.separator + reportFileName, "-p", jmeterProps.toString(), "-d",
					System.getProperty("user.dir"));

			List<String> args = new ArrayList<String>();
			args.addAll(argsTmp);
			args.addAll(getUserProperties());

			if (remote) {
				args.add("-r");
			}

			SecurityManager oldManager = null;
			UncaughtExceptionHandler oldHandler = null;
			if (securityManagerDisabled) {
				// This mess is necessary because JMeter likes to use
				// System.exit.
				// We need to trap the exit call.
				oldManager = System.getSecurityManager();
				System.setSecurityManager(new SecurityManager() {
					@Override
					public void checkExit(int status) {
						throw new ExitException(status);
					}

					@Override
					public void checkPermission(Permission perm, Object context) {
					}

					@Override
					public void checkPermission(Permission perm) {
					}
				});

				oldHandler = Thread.getDefaultUncaughtExceptionHandler();
				Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
					public void uncaughtException(Thread t, Throwable e) {
						if (e instanceof ExitException && ((ExitException) e).getCode() == 0) {
							return; // Ignore
						}
						getLog().error("Error in thread " + t.getName());
					}
				});
			}
			try {
				String[] a_args = args.toArray(new String[] {});
				getLog().info("JMeter Args: " + Arrays.toString(a_args));
				// This mess is necessary because the only way to know when
				// JMeter is done is to wait for its test end message!
				new JMeter().start(a_args);
				BufferedReader in = new BufferedReader(new FileReader(jmeterLog));
				while (!checkForEndOfTest(in)) {
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e) {
						break;
					}
				}
			} catch (ExitException e) {
				if (e.getCode() != 0) {
					throw new MojoExecutionException("Test failed", e);
				}
			} finally {
				if (securityManagerDisabled) {
					System.setSecurityManager(oldManager);
					Thread.setDefaultUncaughtExceptionHandler(oldHandler);
				}
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Can't execute test", e);
		}
	}

	private boolean checkForEndOfTest(BufferedReader in) throws MojoExecutionException {
		boolean testEnded = false;
		try {
			String line;
			while ((line = in.readLine()) != null) {
				if (line.indexOf("Test has ended") != -1) {
					testEnded = true;
					break;
				}
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Can't read log file", e);
		}
		return testEnded;
	}

	private ArrayList<String> getUserProperties() {
		ArrayList<String> propsList = new ArrayList<String>();
		if (jmeterUserProperties == null) {
			return propsList;
		}
		Set<String> keySet = (Set<String>) jmeterUserProperties.keySet();

		for (String key : keySet) {
			propsList.add("-J");
			propsList.add(key + "=" + jmeterUserProperties.get(key));
		}
		return propsList;
	}

	private void addDependenciesToClasspath() {
		try {
			if (compiledClasses != null) {
				getLog().info("Adding depedency: " + compiledClasses);
				ClassPathHacker.addFile(compiledClasses);
			} else {
				getLog().warn("No compiled classes directory specified");
			}

			if (compiledTestClasses != null) {
				getLog().info("Adding depedency: " + compiledTestClasses);
				ClassPathHacker.addFile(compiledTestClasses);
			} else {
				getLog().warn("No compiled test classes directory specified");
			}

			String[] dependencies = getClasspathFiles();
			if (dependencies != null) {
				for (String dependency : dependencies) {
					if (!dependency.contains("maven-")) {
						getLog().debug("Adding depedency: " + dependency);
						ClassPathHacker.addFile(dependency);
					} else {
						getLog().warn("Avoiding depedency: " + dependency);
					}
				}
			}
		} catch (IOException e) {
			getLog().warn("An error occured reading the classpath dump file", e);
		}
	}

	private String[] getClasspathFiles() throws IOException {
		if (classpathDump == null) {
			getLog().warn("The classpath dump file has not been setted");
			return null;
		}

		StringBuffer fileData = new StringBuffer(1000);
		BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(classpathDump)));

		char[] buf = new char[1024];
		int numRead = 0;
		String readData;
		while ((numRead = reader.read(buf)) != -1) {
			readData = String.valueOf(buf, 0, numRead);
			fileData.append(readData);
			buf = new char[1024];
		}
		reader.close();

		String rtn[] = fileData.toString().split(System.getProperty("path.separator"));
		getLog().info("Dependencies found and added to the Classpath: " + rtn.length);

		return rtn;
	}

	private static class ExitException extends SecurityException {
		private static final long serialVersionUID = 5544099211927987521L;

		public int _rc;

		public ExitException(int rc) {
			super(Integer.toString(rc));
			_rc = rc;
		}

		public int getCode() {
			return _rc;
		}
	}

}
