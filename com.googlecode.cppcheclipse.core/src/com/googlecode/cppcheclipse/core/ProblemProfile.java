package com.googlecode.cppcheclipse.core;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.StringTokenizer;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.xml.sax.SAXException;

import com.googlecode.cppcheclipse.core.command.ErrorListCommand;
import com.googlecode.cppcheclipse.core.command.IncompatibleVersionException;
import com.googlecode.cppcheclipse.core.command.ProcessExecutionException;
import com.googlecode.cppcheclipse.core.command.Version;
import com.googlecode.cppcheclipse.core.command.VersionCommand;

/**
 * Maintains all problems, and give back a current profile, with the valid
 * checks
 * 
 * @author Konrad Windszus
 * 
 */
public class ProblemProfile implements Cloneable, IPropertyChangeListener {

	private static final String PROBLEM_DELIMITER = "!";
	private static final String ID_DELIMITER = "=";
	private Map<String, Problem> problems;
	private IConsole console;
	private String binaryPath;

	// TODO: check if duplicate IDs are a problem (see ticket 2302 http://sourceforge.net/apps/trac/cppcheck/ticket/2302)
	public ProblemProfile(IConsole console, String binaryPath)
			throws XPathExpressionException, IOException, InterruptedException,
			ParserConfigurationException, SAXException,
			ProcessExecutionException {
		this.console = console;
		this.binaryPath = binaryPath;
		// check for minimum version
		VersionCommand versionCommand = new VersionCommand(console, binaryPath);
		Version version = versionCommand.run(new NullProgressMonitor());
		if (!version.isCompatible()) {
			throw new IncompatibleVersionException(version);
		}

		initProfileProblems(binaryPath);
	}
	

	public void propertyChange(PropertyChangeEvent event) {
		try {
			// whenever this changes, we have to reload the
			// list of error messages
			binaryPath = (String) event.getNewValue();
			initProfileProblems(binaryPath);
		} catch (Exception e) {
			CppcheclipsePlugin.logError("Error reloading the problems", e);
		}
	}

	private void initProfileProblems(String binaryPath)
			throws XPathExpressionException, IOException, InterruptedException,
			ParserConfigurationException, SAXException,
			ProcessExecutionException {
		problems = new HashMap<String, Problem>();

		ErrorListCommand command = new ErrorListCommand(console, binaryPath);
		Collection<Problem> problemList = command.run();
		for (Problem problem : problemList) {
			// add problems and also check for duplicate ids (=keys)
			if (problems.put(problem.getId(), problem) != null) {
				CppcheclipsePlugin
						.logWarning("Found duplicate id: " + problem.getId());
			}
		}
	}

	public void loadFromPreferences(IPreferenceStore preferences) {
		try {
			String settings = preferences
					.getString(IPreferenceConstants.P_PROBLEMS);

			if (!IPreferenceStore.STRING_DEFAULT_DEFAULT.equals(settings)) {
				StringTokenizer problemsTokenizer = new StringTokenizer(
						settings, PROBLEM_DELIMITER);
				// go through all problems
				while (problemsTokenizer.hasMoreTokens()) {
					String problemSetting = problemsTokenizer.nextToken();
					// extract id per problem
					StringTokenizer problemTokenizer = new StringTokenizer(
							problemSetting, ID_DELIMITER);
					String id = problemTokenizer.nextToken();

					Problem problem = problems.get(id);
					if (problem != null) {
						problem.deserializeNonFinalFields(problemTokenizer
								.nextToken());
					}
				}
			}

		} catch (RuntimeException e) {
			CppcheclipsePlugin.showError(
					"Invalid problem profile preferences found", e);
		}
	}

	public void saveToPreferences(IPreferenceStore preferences) {
		StringBuffer settings = new StringBuffer();
		for (Problem problem : problems.values()) {
			String serialization = problem.serializeNonFinalFields();
			settings.append(problem.getId()).append(ID_DELIMITER).append(
					serialization).append(PROBLEM_DELIMITER);

		}
		preferences.setValue(IPreferenceConstants.P_PROBLEMS, settings
				.toString());
	}

	public void loadDefaults(IPreferenceStore preferences) {
		preferences.setToDefault(IPreferenceConstants.P_PROBLEMS);
		for (Problem problem : problems.values()) {
			problem.setToDefault();
		}
	}

	@Override
	protected Object clone() throws CloneNotSupportedException {
		ProblemProfile profile = (ProblemProfile) super.clone();
		// copy all problems
		profile.problems = new HashMap<String, Problem>();
		for (Problem problem : problems.values()) {
			profile.problems.put(problem.getId(), (Problem) problem.clone());
		}
		return profile;
	}

	public Collection<String> getCategories() {
		Collection<String> categories = new LinkedList<String>();
		for (Problem problem : problems.values()) {
			String category = problem.getCategory();
			if (!categories.contains(category)) {
				categories.add(category);
			}
		}
		return categories;
	}

	public Collection<Problem> getProblemsOfCategory(String category) {
		Collection<Problem> problems = new LinkedList<Problem>();
		for (Problem problem : this.problems.values()) {
			if (category.equals(problem.getCategory())) {
				problems.add(problem);
			}
		}
		return problems;
	}

	public Collection<Problem> getAllProblems() {
		return problems.values();
	}

	public void reportProblems(Collection<Problem> problems,
			IProblemReporter problemReporter,
			SuppressionProfile suppressionProfile) throws CoreException {
		for (Problem problem : problems) {
			if (isProblemEnabled(problem)
					&& !suppressionProfile.isProblemInLineSuppressed(problem
							.getFile(), problem.getId(), problem
							.getLineNumber())) {
				problemReporter.reportProblem(problem);
			}
		}
	}

	public boolean isProblemEnabled(Problem problem) {
		// problems.
		// find problem in profile
		Problem problemInProfile = problems.get(problem.getId());
		if (problemInProfile != null) {
			boolean isEnabled = problemInProfile.isEnabled();

			// also overwrite the severity
			if (isEnabled) {
				problem.setSeverity(problemInProfile.getSeverity());
			}
			return isEnabled;
		}
		return true;
	}

	public String getProblemMessage(String id) {
		Problem problemInProfile = problems.get(id);
		if (problemInProfile == null) {
			return null;
		}
		return problemInProfile.getMessage();
	}
}
