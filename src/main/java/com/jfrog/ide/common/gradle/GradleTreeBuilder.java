package com.jfrog.ide.common.gradle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Sets;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.jfrog.build.api.util.Log;
import org.jfrog.build.extractor.scan.DependencyTree;
import org.jfrog.build.extractor.scan.GeneralInfo;
import org.jfrog.build.extractor.scan.License;
import org.jfrog.build.extractor.scan.Scope;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Build Gradle dependency tree before the Xray scan.
 *
 * @author yahavi
 */
@SuppressWarnings({"unused"})
public class GradleTreeBuilder {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final GradleDriver gradleDriver;
    private final Path projectDir;

    public GradleTreeBuilder(Path projectDir, Map<String, String> env) {
        this.projectDir = projectDir;
        this.gradleDriver = new GradleDriver(projectDir, env);
    }

    /**
     * Build the Gradle dependency tree.
     *
     * @param logger - The logger.
     * @return full dependency tree without Xray scan results.
     * @throws IOException in case of I/O error.
     */
    public DependencyTree buildTree(Log logger) throws IOException {
        if (!gradleDriver.isGradleInstalled()) {
            logger.error("Could not scan Gradle project dependencies, because Gradle CLI is not in the PATH.");
            return null;
        }
        File[] gradleDependenciesFiles = gradleDriver.generateDependenciesGraphAsJson(projectDir.toFile(), logger);
        gradleDependenciesFiles = ArrayUtils.nullToEmpty(gradleDependenciesFiles, File[].class);
        try {
            return createDependencyTrees(gradleDependenciesFiles);
        } finally {
            if (!ArrayUtils.isEmpty(gradleDependenciesFiles)) {
                FileUtils.deleteDirectory(gradleDependenciesFiles[0].getParentFile());
            }
        }
    }

    /**
     * Create dependency trees from files generated by running the 'generateDependenciesGraphAsJson' task.
     *
     * @param gradleDependenciesFiles - The files containing the dependency trees
     * @return a dependency tree contain one or more Gradle projects.
     * @throws IOException in case of any I/O error.
     */
    private DependencyTree createDependencyTrees(File[] gradleDependenciesFiles) throws IOException {
        DependencyTree rootNode = new DependencyTree(projectDir.getFileName().toString());
        rootNode.setGeneralInfo(new GeneralInfo().componentId(projectDir.getFileName().toString()).path(projectDir.toString()));
        for (File projectFile : gradleDependenciesFiles) {
            GradleDependencyNode node = objectMapper.readValue(projectFile, GradleDependencyNode.class);
            GeneralInfo generalInfo = createGeneralInfo(node).path(projectDir.toString());
            DependencyTree projectNode = createNode(generalInfo, node);
            populateDependencyTree(projectNode, node);
            rootNode.add(projectNode);
        }
        if (gradleDependenciesFiles.length == 1) {
            rootNode = (DependencyTree) rootNode.getFirstChild();
        }
        return rootNode;
    }

    /**
     * Recursively populate a Gradle dependency node.
     *
     * @param node                 - The dependency node to populate
     * @param gradleDependencyNode - The Gradle dependency node created by 'generateDependenciesGraphAsJson'
     */
    private void populateDependencyTree(DependencyTree node, GradleDependencyNode gradleDependencyNode) {
        for (GradleDependencyNode gradleDependencyChild : CollectionUtils.emptyIfNull(gradleDependencyNode.getDependencies())) {
            GeneralInfo generalInfo = createGeneralInfo(gradleDependencyChild);
            DependencyTree child = createNode(generalInfo, gradleDependencyChild);
            node.add(child);
            populateDependencyTree(child, gradleDependencyChild);
        }
    }

    private GeneralInfo createGeneralInfo(GradleDependencyNode node) {
        return new GeneralInfo()
                .groupId(node.getGroupId())
                .artifactId(node.getArtifactId())
                .version(node.getVersion())
                .pkgType("gradle");
    }

    /**
     * Create a dependency tree node.
     *
     * @param generalInfo          - The dependency General info
     * @param gradleDependencyNode - The Gradle dependency node created by 'generateDependenciesGraphAsJson'
     * @return the dependency tree node.
     */
    private DependencyTree createNode(GeneralInfo generalInfo, GradleDependencyNode gradleDependencyNode) {
        DependencyTree node = new DependencyTree(getNodeName(generalInfo, gradleDependencyNode.isUnresolved()));
        node.setGeneralInfo(generalInfo);
        Set<Scope> scopes = CollectionUtils.emptyIfNull(gradleDependencyNode.getScopes()).stream().map(Scope::new).collect(Collectors.toSet());
        if (scopes.isEmpty()) {
            scopes.add(new Scope());
        }
        node.setScopes(scopes);
        node.setLicenses(Sets.newHashSet(new License()));
        return node;
    }

    /**
     * Get the dependency tree node name.
     * If the dependency is a subproject, the name is the project artifact name.
     * If the dependency is a regular dependency, the name is a GAV string.
     * If the dependency is unresolved, add an [unresolved] suffix.
     *
     * @param generalInfo - The dependency general info
     * @param unresolved  - True if the dependency could not be resolved
     * @return the dependency tree node name.
     */
    private String getNodeName(GeneralInfo generalInfo, boolean unresolved) {
        String unresolvedStr = unresolved ? " [unresolved]" : "";
        if (StringUtils.isBlank(generalInfo.getPath())) {
            return generalInfo.getGroupId() + ":" + generalInfo.getArtifactId() + ":" + generalInfo.getVersion() + unresolvedStr;
        }
        return generalInfo.getArtifactId() + unresolvedStr;
    }
}
