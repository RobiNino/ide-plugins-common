package com.jfrog.ide.common.npm;

import com.google.common.collect.Sets;
import org.apache.commons.io.FileUtils;
import org.jfrog.build.api.util.NullLog;
import org.jfrog.build.extractor.npm.NpmDriver;
import org.jfrog.build.extractor.scan.DependencyTree;
import org.jfrog.build.extractor.scan.GeneralInfo;
import org.jfrog.build.extractor.scan.Scope;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.testng.collections.Lists;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import static org.testng.Assert.*;

/**
 * Test correctness of DependencyTree for different npm projects.
 * The tests verifies correctness before and after 'npm install' command.
 *
 * @author yahavi
 */
public class NpmTreeBuilderTest {
    private static final Path NPM_ROOT = Paths.get(".").toAbsolutePath().normalize().resolve(Paths.get("src", "test", "resources", "npm"));

    enum Project {
        EMPTY("package-name1", "empty", false),
        DEPENDENCY("package-name2", "dependency", true),
        DEPENDENCY_PACKAGE_LOCK("package-name3", "dependencyPackageLock", true),
        DEV_AND_PROD("package-name4", "devAndProd", true);

        private final boolean hasChildren;
        private final String name;
        private final Path path;

        Project(String name, String path, boolean hasChildren) {
            this.name = name;
            this.path = NPM_ROOT.resolve(path);
            this.hasChildren = hasChildren;
        }
    }

    private final NpmDriver npmDriver = new NpmDriver(null);
    private File tempProject;

    @BeforeMethod
    public void setUp(Object[] testArgs) {
        try {
            tempProject = Files.createTempDirectory("ide-plugins-common-npm").toFile();
            tempProject.deleteOnExit();
            FileUtils.copyDirectory(((Project) testArgs[0]).path.toFile(), tempProject);
        } catch (IOException e) {
            fail(e.getMessage(), e);
        }
    }

    @AfterMethod
    public void tearDown() {
        FileUtils.deleteQuietly(tempProject);
    }

    @DataProvider
    private Object[][] npmTreeBuilderProvider() {
        return new Object[][]{
                {Project.EMPTY, false, 0},
                {Project.EMPTY, true, 0},
                {Project.DEPENDENCY, false, 0},
                {Project.DEPENDENCY, true, 2},
                {Project.DEPENDENCY_PACKAGE_LOCK, false, 2},
                {Project.DEPENDENCY_PACKAGE_LOCK, true, 2},
                {Project.DEV_AND_PROD, false, 0},
                {Project.DEV_AND_PROD, true, 1},
        };
    }

    @SuppressWarnings("unused")
    @Test(dataProvider = "npmTreeBuilderProvider")
    private void npmTreeBuilderTest(Project project, boolean install, int expectedChildren) {
        try {
            if (install) {
                npmDriver.install(tempProject, Lists.newArrayList(), null);
            }
            NpmTreeBuilder npmTreeBuilder = new NpmTreeBuilder(tempProject.toPath(), null);
            DependencyTree dependencyTree = npmTreeBuilder.buildTree(new NullLog());
            assertNotNull(dependencyTree);
            String expectedProjectName = project.name;
            if (!install && project.hasChildren) {
                expectedProjectName += " (Not installed)";
            }
            checkGeneralInfo(dependencyTree.getGeneralInfo(), expectedProjectName, tempProject);
            assertEquals(dependencyTree.getChildren().size(), expectedChildren);
            switch (expectedChildren) {
                case 0:
                    noChildrenScenario(dependencyTree);
                    break;
                case 1:
                    oneChildScenario(dependencyTree, expectedProjectName);
                    break;
                case 2:
                    twoChildrenScenario(dependencyTree, expectedProjectName);
            }
        } catch (IOException e) {
            fail(e.getMessage(), e);
        }
    }

    private void checkGeneralInfo(GeneralInfo actual, String name, File path) {
        assertNotNull(actual);
        assertEquals(actual.getComponentId(), name + ":" + "0.0.1");
        assertEquals(actual.getPath(), path.toString());
        assertEquals(actual.getPkgType(), "npm");
        assertEquals(actual.getArtifactId(), name);
        assertEquals(actual.getVersion(), "0.0.1");
    }

    private void noChildrenScenario(DependencyTree dependencyTree) {
        assertTrue(dependencyTree.isLeaf());
    }

    private void oneChildScenario(DependencyTree dependencyTree, String expectedProjectName) {
        DependencyTree child = dependencyTree.getChildren().get(0);
        assertEquals("progress:2.0.3", child.toString());
        Set<Scope> expectedScopes = Sets.newHashSet(new Scope("prod"), new Scope("dev"));
        assertEquals(child.getScopes(), expectedScopes);
        assertEquals(child.getParent().toString(), expectedProjectName);
    }

    private void twoChildrenScenario(DependencyTree dependencyTree, String expectedProjectName) {
        for (DependencyTree child : dependencyTree.getChildren()) {
            switch (child.toString()) {
                case "progress:2.0.3":
                    assertEquals(child.getScopes(), Sets.newHashSet(new Scope("prod")));
                    break;
                case "debug:4.1.1":
                    assertEquals(child.getScopes(), Sets.newHashSet(new Scope("dev")));
                    break;
                default:
                    fail("Unexpected dependency " + child);
            }
            assertEquals(child.getParent().toString(), expectedProjectName);
        }
    }
}