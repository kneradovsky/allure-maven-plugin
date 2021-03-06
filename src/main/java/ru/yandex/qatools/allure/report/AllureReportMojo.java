package ru.yandex.qatools.allure.report;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FalseFileFilter;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.doxia.siterenderer.Renderer;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.reporting.AbstractMavenReport;
import org.apache.maven.reporting.MavenReportException;
import org.codehaus.plexus.util.DirectoryScanner;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import ru.yandex.qatools.clay.Aether;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * @author Dmitry Baev charlie@yandex-team.ru
 *         Date: 09.06.14
 */
@SuppressWarnings("unused")
@Mojo(name = "report", defaultPhase = LifecyclePhase.SITE)
public class AllureReportMojo extends AbstractMavenReport {

    public static final String DATA = "data";
    public static final String TESTCASE_JSON = ".+-testcase\\.json";
    public static final String COLON = ":";

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File projectBaseDirectory;

    @Parameter(defaultValue = "${project.reporting.outputDirectory}", readonly = true)
    private File reportingDirectory;

    @Parameter(property = "allure.report.directory", required = false,
            defaultValue = "${project.reporting.outputDirectory}/allure-maven-plugin")
    private File outputDirectory;

    @Parameter(property = "allure.results.pattern", required = false,
            defaultValue = "**/allure-results")
    private String resultsPattern;

    @Parameter(property = "allure.version", required = false,
            defaultValue = "1.3.9")
    private String reportVersion;

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    protected MavenProject project;

    /**
     * Fail the report generation if no report data found in given report directories.
     */
    @Parameter(defaultValue = "false")
    protected boolean failReportIfEmpty;

    @Component
    protected PluginDescriptor pluginDescriptor;

    @Component
    protected Renderer siteRenderer;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> projectRepos;

    @Parameter(defaultValue = "${project.remotePluginRepositories}", readonly = true)
    private List<RemoteRepository> pluginRepos;

    @Parameter
    protected List<Dependency> plugins = Collections.emptyList();

    @Override
    protected Renderer getSiteRenderer() {
        return siteRenderer;
    }

    @Override
    protected String getOutputDirectory() {
        return outputDirectory.getAbsolutePath();
    }

    @Override
    protected MavenProject getProject() {
        return project;
    }

    @Override
    protected void executeReport(Locale locale) throws MavenReportException {
        getLog().info("Report Version: " + reportVersion);
        getLog().info("Results Pattern: " + resultsPattern);

        File[] reportDirectories = getReportDirectories(projectBaseDirectory, resultsPattern);

        logDirectories(reportDirectories);
        logPlugins();

        checkDirectories(reportDirectories);

        try {
            AllureReportBuilder builder = createReportBuilder();
            for (Dependency plugin : getPlugins()) {
                builder.addExtension(getCoordinates(plugin));
            }

            getLog().info("Generate report to " + outputDirectory);
            builder.processResults(reportDirectories);

            if (failReportIfEmpty && isReportEmpty()) {
                throw new MavenReportException("There are no allure results found.");
            }

            getLog().info("Report data generated successfully. Unpack report face...");
            builder.unpackFace();

            getLog().info("Report unpacked successfully.");
            render(getSink(), getName(locale));
        } catch (AllureReportBuilderException e) {
            getLog().error("Can't generate allure report data", e);
            throw new MavenReportException("Can't generate allure report data", e);
        }
    }

    /**
     * Get coordinates in Aether format {@code <groupId>:<artifactId>[:<extension>[:<classifier>]]:<version>}.
     */
    protected String getCoordinates(Dependency dependency) {
        StringBuilder builder = new StringBuilder();
        builder.append(dependency.getGroupId())
                .append(COLON)
                .append(dependency.getArtifactId())
                .append(COLON)
                .append(dependency.getType())
                .append(COLON);

        if (dependency.getClassifier() != null) {
            builder.append(dependency.getClassifier()).append(COLON);
        }

        builder.append(dependency.getVersion());
        return builder.toString();
    }

    /**
     * Create a report builder.
     */
    protected AllureReportBuilder createReportBuilder() throws AllureReportBuilderException {
        Aether aether = Aether.aether(repoSystem, repoSession, projectRepos);
        AllureReportBuilder builder = new AllureReportBuilder(reportVersion, outputDirectory, aether);
        builder.setClassLoader(Thread.currentThread().getContextClassLoader());
        return builder;
    }

    /**
     * Check that there are at least one result directory.
     */
    protected void checkDirectories(File[] reportDirectories) throws MavenReportException {
        if (reportDirectories.length == 0) {
            throw new MavenReportException(String.format("Can't find any results directories by pattern [%s]",
                    resultsPattern));
        }
    }

    /**
     * Write information about found plugins.
     */
    protected void logPlugins() {
        getLog().info(String.format("Found [%d] plugins", getPlugins().size()));
        for (Dependency plugin : getPlugins()) {
            getLog().info(plugin.toString());
        }
    }

    /**
     * Write an absolute directory path for each given directory to the log.
     */
    protected void logDirectories(File[] directories) {
        getLog().info(String.format("Found [%s] results directories by pattern [%s]",
                directories.length, resultsPattern));
        for (File directory : directories) {
            getLog().info(directory.getAbsolutePath());
        }
    }

    /**
     * Find all test cases in data directory. Returns false if there are some
     * test cases files present true otherwise.
     */
    protected boolean isReportEmpty() {
        File data = new File(outputDirectory, DATA);
        if (data.isDirectory()) {
            Collection<File> files = FileUtils.listFiles(data,
                    new RegexFileFilter(TESTCASE_JSON), FalseFileFilter.INSTANCE);
            return files.isEmpty();
        }
        return true;
    }

    /**
     * Render allure report page in project-reports.html.
     *
     * @param sink  sink
     * @param title title
     */
    private void render(Sink sink, String title) {
        sink.head();
        sink.title();
        sink.text(title);
        sink.title_();
        sink.head_();
        sink.body();

        sink.lineBreak();

        File indexHtmlFile = new File(outputDirectory, "index.html");
        String relativePath = reportingDirectory.toURI().relativize(indexHtmlFile.toURI()).getPath();

        sink.rawText(String.format("<meta http-equiv=\"refresh\" content=\"0;url=%s\" />",
                relativePath));

        sink.link(relativePath);

        sink.body_();
        sink.flush();
        sink.close();
    }

    /**
     * Get list of plugins.
     */
    protected List<Dependency> getPlugins() {
        return plugins;
    }

    /**
     * Get the base name used to create report's output file(s).
     *
     * @return the output name of this report.
     */
    @Override
    public String getOutputName() {
        return pluginDescriptor.getArtifactId();
    }

    /**
     * Get the localized report name.
     *
     * @param locale the wanted locale to return the report's name, could be null.
     * @return the name of this report.
     */
    @Override
    public String getName(Locale locale) {
        return "Allure";
    }

    /**
     * Get the localized report description.
     *
     * @param locale the wanted locale to return the report's description, could be null.
     * @return the description of this report.
     */
    @Override
    public String getDescription(Locale locale) {
        return "Extended report on the test results of the project.";
    }

    private File[] getReportDirectories(File baseDir, String globs) {
        if (new File(globs).isAbsolute()) {
            return getPathsByAbsolute(globs);
        } else {
            return getPathsByGlobs(baseDir, globs);
        }
    }

    private File[] getPathsByAbsolute(String absolute) {
        return new File[]{new File(absolute)};
    }

    private File[] getPathsByGlobs(File baseDir, String globs) {
        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir(baseDir);
        scanner.setIncludes(new String[]{globs});
        scanner.setCaseSensitive(false);
        scanner.scan();

        String[] relativePaths = scanner.getIncludedDirectories();
        File[] absolutePaths = new File[relativePaths.length];
        for (int i = 0; i < relativePaths.length; i++) {
            absolutePaths[i] = new File(baseDir, relativePaths[i]);
        }
        return absolutePaths;
    }

}
