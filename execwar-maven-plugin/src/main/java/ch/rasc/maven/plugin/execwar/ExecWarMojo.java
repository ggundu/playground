package ch.rasc.maven.plugin.execwar;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.jar.JarArchiveEntry;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.jar.Manifest;
import org.codehaus.plexus.archiver.jar.ManifestException;
import org.sonatype.aether.RepositorySystem;
import org.sonatype.aether.RepositorySystemSession;
import org.sonatype.aether.repository.RemoteRepository;
import org.sonatype.aether.resolution.ArtifactRequest;
import org.sonatype.aether.resolution.ArtifactResolutionException;
import org.sonatype.aether.resolution.ArtifactResult;
import org.sonatype.aether.util.artifact.DefaultArtifact;

import ch.rasc.maven.plugin.execwar.run.DeleteDirectory;
import ch.rasc.maven.plugin.execwar.run.Runner;

@Mojo(name = "build", defaultPhase = LifecyclePhase.PACKAGE)
public class ExecWarMojo extends AbstractMojo {

	@Component
	private MavenProject project;

	@Component
	private RepositorySystem repoSystem;

	@Parameter(defaultValue = "${repositorySystemSession}")
	private RepositorySystemSession repoSession;

	@Parameter(defaultValue = "${project.remotePluginRepositories}")
	private List<RemoteRepository> remoteRepos;

	@Parameter(defaultValue = "${plugin.artifacts}", required = true)
	private List<Artifact> pluginArtifacts;

	@Parameter(defaultValue = "${project.build.directory}", required = true)
	private String buildDirectory;

	@Parameter(defaultValue = "${project.build.directory}/execwar")
	private String pluginWorkDirectory;

	@Parameter(property = "execwar.jar.finalName", defaultValue = "${project.artifactId}-${project.version}-war-exec.jar", required = true)
	private String finalName;

	@Parameter
	private List<Dependency> extraDependencies;

	@Override
	public void execute() throws MojoExecutionException {

		Path warExecFile = Paths.get(buildDirectory, finalName);
		try {
			Files.deleteIfExists(warExecFile);
			Files.createDirectories(warExecFile.getParent());

			try (OutputStream os = Files.newOutputStream(warExecFile);
					ArchiveOutputStream aos = new ArchiveStreamFactory().createArchiveOutputStream(
							ArchiveStreamFactory.JAR, os)) {

				File projectArtifact = project.getArtifact().getFile();
				if (projectArtifact != null && Files.exists(projectArtifact.toPath())) {
					aos.putArchiveEntry(new JarArchiveEntry(projectArtifact.getName()));
					try (InputStream is = Files.newInputStream(projectArtifact.toPath())) {
						IOUtils.copy(is, aos);
					}
					aos.closeArchiveEntry();
				}

				Set<String> includeGroupIds = new HashSet<>();
				includeGroupIds.add("org.apache.tomcat");
				includeGroupIds.add("org.apache.tomcat.embed");
				includeGroupIds.add("ecj");
				includeGroupIds.add("org.yaml");
				includeGroupIds.add("net.sourceforge.argparse4j");
				includeGroupIds.add("commons-daemon");

				for (Artifact pluginArtifact : pluginArtifacts) {
					if (includeGroupIds.contains(pluginArtifact.getGroupId())) {
						try (JarFile jarFile = new JarFile(pluginArtifact.getFile())) {
							extractJarToArchive(jarFile, aos);
						}
					}
				}

				if (extraDependencies != null) {
					for (Dependency dependency : extraDependencies) {

						ArtifactRequest request = new ArtifactRequest();
						request.setArtifact(new DefaultArtifact(dependency.getGroupId(), dependency.getArtifactId(),
								dependency.getType(), dependency.getVersion()));
						request.setRepositories(remoteRepos);

						getLog().info("Resolving artifact " + dependency + " from " + remoteRepos);

						ArtifactResult result;
						try {
							result = repoSystem.resolveArtifact(repoSession, request);
						} catch (ArtifactResolutionException e) {
							throw new MojoExecutionException(e.getMessage(), e);
						}

						try (JarFile jarFile = new JarFile(result.getArtifact().getFile())) {
							extractJarToArchive(jarFile, aos);
						}
					}
				}

				aos.putArchiveEntry(new JarArchiveEntry("conf/web.xml"));
				IOUtils.copy(getClass().getResourceAsStream("/conf/web.xml"), aos);
				aos.closeArchiveEntry();

				Class<?>[] runnerClasses = new Class<?>[]{Runner.class, DeleteDirectory.class};
				for (Class<?> rc : runnerClasses) {
					String className = rc.getName();
					String classAsPath = className.replace('.', '/') + ".class";

					try (InputStream is = getClass().getResourceAsStream("/" + classAsPath)) {
						aos.putArchiveEntry(new JarArchiveEntry(classAsPath));
						IOUtils.copy(is, aos);
						aos.closeArchiveEntry();
					}					
				}				

				Manifest manifest = new Manifest();

				Manifest.Attribute mainClassAtt = new Manifest.Attribute();
				mainClassAtt.setName("Main-Class");
				mainClassAtt.setValue(Runner.class.getName());
				manifest.addConfiguredAttribute(mainClassAtt);

				aos.putArchiveEntry(new JarArchiveEntry("META-INF/MANIFEST.MF"));
				manifest.write(aos);
				aos.closeArchiveEntry();

				aos.putArchiveEntry(new JarArchiveEntry("EXECWAR_TIMESTAMP"));
				aos.write(String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
				aos.closeArchiveEntry();

			}
		} catch (IOException | ArchiveException | ManifestException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	// private static Set<String> fileNames = new HashSet<>();

	private static void extractJarToArchive(JarFile file, ArchiveOutputStream aos) throws IOException {
		Enumeration<? extends JarEntry> entries = file.entries();
		while (entries.hasMoreElements()) {
			JarEntry j = entries.nextElement();
			if (!"META-INF/MANIFEST.MF".equals(j.getName())) {
				// if (fileNames.contains(j.getName())) {
				// System.out.println(j.getName());
				// } else {
				// fileNames.add(j.getName());
				// }
				aos.putArchiveEntry(new JarArchiveEntry(j.getName()));
				IOUtils.copy(file.getInputStream(j), aos);
				aos.closeArchiveEntry();
			}
		}
	}
}