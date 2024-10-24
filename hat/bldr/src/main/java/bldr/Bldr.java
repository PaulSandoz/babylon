/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package bldr;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static java.io.IO.println;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.Files.isRegularFile;

public class Bldr {
    public interface PathHolder {
        Path path();
    }

    interface TargetDirProvider extends PathHolder {
        Path targetDir();
    }

    interface JavaSourceDirProvider {
        Path javaSourceDir();
    }

    interface ResourceDirProvider {
        Path resourcesDir();
    }

    public interface DirPathHolder extends PathHolder {
    }

    public interface FilePathHolder extends PathHolder {
    }

    public interface ClassPathEntry extends PathHolder {
    }

    public record ClassDir(Path path) implements ClassPathEntry, DirPathHolder {
    }

    public record JarFile(Path path) implements ClassPathEntry, FilePathHolder {
    }

    public record SourcePathEntry(Path path) implements DirPathHolder {
    }

    public interface SourceFile extends FilePathHolder {
    }

    public record JavaSourceFile(Path path) implements SourceFile {
    }

    public record CppSourceFile(Path path) implements SourceFile {
    }

    public record HeaderSourceFile(Path path) implements SourceFile {
    }


    public record ClassPath(List<ClassPathEntry> entries) {
    }

    public record SourcePath(List<SourcePathEntry> entries) {
    }

    public record XMLFile(Path path) implements FilePathHolder {
    }


    public static class Repo {
        public record Id(Repo repo, String groupId, String artifactId, String versionId) {
            static String groupId(XMLNode xmlNode) {
                return xmlNode.xpathQueryString("groupId/text()");
            }

            static String artifactId(XMLNode xmlNode) {
                return xmlNode.xpathQueryString("artifactId/text()");
            }

            static String versionId(XMLNode xmlNode) {
                return xmlNode.xpathQueryString("versionId/text()");
            }

            public Id(Repo repo, XMLNode xmlNode) {
                this(repo, groupId(xmlNode), artifactId(xmlNode), versionId(xmlNode));
            }

            private String artifactAndVersion() {
                return artifactId() + '-' + versionId();
            }

            private String pathName() {
                return groupId() + '.' + artifactAndVersion();
            }

            private String name(String suffix) {
                return artifactAndVersion() + "." + suffix;
            }
        }

        private final String repoBase = "https://repo1.maven.org/maven2/";
        private final String searchBase = "https://search.maven.org/solrsearch/";
        private Path path;

        public Repo(Path path) {
            this.path = path;
        }

        public XMLNode select(String query) {
            try {
                URL url = new URI(searchBase + "select?q=" +
                        URLEncoder.encode(query, StandardCharsets.UTF_8)
                        + "&core=gav&wt=xml").toURL();
                try {
                    return new XMLNode(url);
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            } catch (MalformedURLException | URISyntaxException e) {
                throw new RuntimeException(e);
            }
        }

        public XMLNode queryXMLByGroup(String groupId) {
            return select("g:" + groupId);
        }

        public XMLNode queryXMLByArtifact(String artifactId) {
            return select("a:" + artifactId);
        }

        public XMLNode queryByGroupAndArtifact(String groupId, String artifactId) {
            return select("g:" + groupId + " AND a:" + artifactId);
        }

        public XMLNode queryByGroupArtifactAndVersion(String groupId, String artifactId, String versionId) {
            return select("g:" + groupId + " AND a:" + artifactId + " AND v:" + versionId);
        }

        public Optional<Id> id(String groupId, String artifactId, String versionId) {
            var xmlNode = queryByGroupArtifactAndVersion(groupId, artifactId, versionId);
            var numFound = xmlNode.xpathQueryString("/response/result/@numFound");
            if (numFound.isEmpty() || numFound.equals("0")) {
                return Optional.empty();
            } else {
                return Optional.of(new Id(this, groupId, artifactId, versionId));
            }
        }

        public Stream<String> versions(String groupId, String artifactId) {
            var xmlNode = queryByGroupAndArtifact(groupId, artifactId);
            return xmlNode.xmlNodes(xmlNode.xpath("/response/result/doc"))
                    .map(xmln -> xmln.xpathQueryString("str[@name='v']/text()"));
        }

        public boolean forEachVersion(String groupId, String artifactId, Consumer<String> idConsumer) {
            boolean[] found = new boolean[]{false};
            versions(groupId, artifactId).forEach(id -> {
                idConsumer.accept(id);
                found[0] = true;
            });
            return found[0];
        }

        public Stream<Id> ids(String groupId, String artifactId) {
            var xmlNode = queryByGroupAndArtifact(groupId, artifactId);
            var numFound = xmlNode.xpathQueryString("/response/result/@numFound");
            if (numFound.isEmpty() || numFound.equals("0")) {
                return Stream.empty();
            } else {
                return xmlNode.xmlNodes(xmlNode.xpath("/response/result/doc"))
                        .map(xmln -> {
                            var a = xmln.xpathQueryString("str[@name='a']/text()");
                            var g = xmln.xpathQueryString("str[@name='g']/text()");
                            var v = xmln.xpathQueryString("str[@name='v']/text()");
                            return new Id(this, g, a, v);
                        });
            }
        }

        public boolean forEachId(String groupId, String artifactId, Consumer<Id> idConsumer) {
            boolean[] found = new boolean[]{false};
            ids(groupId, artifactId).forEach(id -> {
                idConsumer.accept(id);
                found[0] = true;
            });
            return found[0];
        }


        // https://search.maven.org/solrsearch/select?q=a:aparapi&core=gav&wt=xml
        //https://search.maven.org/solrsearch/select?q=aparapi&wt=xml
        //https://repo1.maven.org/maven2/com/aparapi/aparapi-jni/1.0.0/aparapi-jni-1.0.0.pom
        //https://repo1.maven.org/maven2/com/aparapi/aparapi-jni/maven-metadata.xml
        //
    }

    public record OS(String arch, String name, String version) {
        static final String MacName = "Mac OS X";
        static final String LinuxName = "Linux";
        public String nameArchTuple() {
            return switch (name()) {
                case MacName -> "macos";
                default -> name().toLowerCase();
            } + '-' + arch();
        }

        public boolean isMac() {
            return name().equals(MacName);
        }

        public boolean isLinux() {
            return name().equals(LinuxName);
        }

        public Path macAppLibFrameworks() {
            return Path.of("/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/"
                    + "MacOSX.sdk/System/Library/Frameworks");
        }

        public Path macFrameworkHeaderFile(String resolveMe) {
            return macAppLibFrameworks().resolve(resolveMe);
        }

        public Path macLibFrameworks() {
            return Path.of("/System/Library/Frameworks");
        }

        public Path macFramework(String resolveMe) {
            return macLibFrameworks().resolve(resolveMe);
        }
    }

    public static OS os = new OS(System.getProperty("os.arch"), System.getProperty("os.name"), System.getProperty("os.version"));

    public record Java(String version, File home) {
    }

    public static Java java = new Java(System.getProperty("java.version"), new File(System.getProperty("java.home")));

    public record User(File home, File pwd) {
    }

    public static User user = new User(new File(System.getProperty("user.home")), new File(System.getProperty("user.dir")));


    public static class XMLNode {
        org.w3c.dom.Element element;
        List<XMLNode> children = new ArrayList<>();
        Map<String, String> attrMap = new HashMap<>();

        XMLNode(org.w3c.dom.Element element) {
            this.element = element;
            this.element.normalize();
            NodeList nodeList = element.getChildNodes();
            for (int i = 0; i < nodeList.getLength(); i++) {
                if (nodeList.item(i) instanceof org.w3c.dom.Element e) {
                    this.children.add(new XMLNode(e));
                }
            }
            for (int i = 0; i < element.getAttributes().getLength(); i++) {
                if (element.getAttributes().item(i) instanceof org.w3c.dom.Attr attr) {
                    this.attrMap.put(attr.getName(), attr.getValue());
                }
            }
        }

        public boolean hasAttr(String name) {
            return attrMap.containsKey(name);
        }

        public String attr(String name) {
            return attrMap.get(name);
        }

        XMLNode(Path path) throws Throwable {
            this(javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path.toFile()).getDocumentElement());
        }

        XMLNode(File file) throws Throwable {
            this(javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getDocumentElement());
        }

        XMLNode(URL url) throws Throwable {
            this(javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(url.openStream()).getDocumentElement());
        }

        void write(StreamResult streamResult) throws Throwable {
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
            transformer.transform(new DOMSource(element.getOwnerDocument()), streamResult);
        }

        void write(File file) throws Throwable {
            write(new StreamResult(file));
        }

        @Override
        public String toString() {
            var stringWriter = new StringWriter();
            try {
                var transformer = TransformerFactory.newInstance().newTransformer();
                transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                transformer.setOutputProperty(OutputKeys.METHOD, "xml");
                transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
                transformer.transform(new DOMSource(element), new StreamResult(stringWriter));
                return stringWriter.toString();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }

        }

        XPathExpression xpath(String expression) {
            XPath xpath = XPathFactory.newInstance().newXPath();
            try {
                return xpath.compile(expression);
            } catch (XPathExpressionException e) {
                throw new RuntimeException(e);
            }
        }

        Node node(XPathExpression xPathExpression) {
            try {
                return (Node) xPathExpression.evaluate(this.element, XPathConstants.NODE);
            } catch (XPathExpressionException e) {
                throw new RuntimeException(e);
            }
        }

        String str(XPathExpression xPathExpression) {
            try {
                return (String) xPathExpression.evaluate(this.element, XPathConstants.STRING);
            } catch (XPathExpressionException e) {
                throw new RuntimeException(e);
            }
        }

        String xpathQueryString(String xpathString) {
            try {
                return (String) xpath(xpathString).evaluate(this.element, XPathConstants.STRING);
            } catch (XPathExpressionException e) {
                throw new RuntimeException(e);
            }
        }

        NodeList nodeList(XPathExpression xPathExpression) {
            try {
                return (NodeList) xPathExpression.evaluate(this.element, XPathConstants.NODESET);
            } catch (XPathExpressionException e) {
                throw new RuntimeException(e);
            }
        }

        Stream<org.w3c.dom.Node> nodes(XPathExpression xPathExpression) {
            var nodeList = nodeList(xPathExpression);
            List<org.w3c.dom.Node> nodes = new ArrayList<>();
            for (int i = 0; i < nodeList.getLength(); i++) {
                nodes.add(nodeList.item(i));
            }
            return nodes.stream();
        }

        Stream<org.w3c.dom.Element> elements(XPathExpression xPathExpression) {
            return nodes(xPathExpression).filter(n -> n instanceof org.w3c.dom.Element).map(n -> (Element) n);
        }

        Stream<XMLNode> xmlNodes(XPathExpression xPathExpression) {
            return elements(xPathExpression).map(e -> new XMLNode(e));
        }
    }

    /*
        static class POM {
            static Pattern varPattern = Pattern.compile("\\$\\{([^}]*)\\}");
            static public String varExpand(Map<String, String> props, String value) { // recurse
                String result = value;
                if (varPattern.matcher(value) instanceof Matcher matcher && matcher.find()) {
                    var v = matcher.groupId(1);
                    result = varExpand(props, value.substring(0, matcher.start())
                            + (v.startsWith("env")
                            ? System.getenv(v.substring(4))
                            : props.get(v))
                            + value.substring(matcher.end()));
                    //out.println("incomming ='"+value+"'  v= '"+v+"' value='"+value+"'->'"+result+"'");
                }
                return result;
            }

            POM(Path dir) throws Throwable {
                var topPom = new XMLNode(new File(dir.toFile(), "pom.xml"));
                var babylonDirKey = "babylon.dir";
                var spirvDirKey = "beehive.spirv.toolkit.dir";
                var hatDirKey = "hat.dir";
                var interestingKeys = Set.of(spirvDirKey, babylonDirKey, hatDirKey);
                var requiredDirKeys = Set.of(babylonDirKey, hatDirKey);
                var dirKeyToDirMap = new HashMap<String, File>();
                var props = new HashMap<String, String>();

                topPom.children.stream().filter(e -> e.element.getNodeName().equals("properties")).forEach(properties ->
                        properties.children.stream().forEach(property -> {
                            var key = property.element.getNodeName();
                            var value = varExpand(props, property.element.getTextContent());
                            props.put(key, value);
                            if (interestingKeys.contains(key)) {
                                var file = new File(value);
                                if (requiredDirKeys.contains(key) && !file.exists()) {
                                    System.err.println("ERR pom.xml has property '" + key + "' with value '" + value + "' but that dir does not exists!");
                                    System.exit(1);
                                }
                                dirKeyToDirMap.put(key, file);
                            }
                        })
                );
                for (var key : requiredDirKeys) {
                    if (!props.containsKey(key)) {
                        System.err.println("ERR pom.xml expected to have property '" + key + "' ");
                        System.exit(1);
                    }
                }
            }
        }
    */
    public static String pathCharSeparated(List<Path> paths) {
        StringBuilder sb = new StringBuilder();
        paths.forEach(path -> {
            if (!sb.isEmpty()) {
                sb.append(File.pathSeparatorChar);
            }
            sb.append(path);
        });
        return sb.toString();
    }

    public static Path rmdir(Path path) {
        try {
            if (Files.exists(path)) {
                Files.walk(path).sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        } catch (IOException ioe) {
            System.out.println(ioe);
        }
        return path;
    }

    public static Stream<Path> subDirStream(Path path, String... dirNames) {
        return Stream.of(dirNames).map(path::resolve).filter(Files::isDirectory);
    }

    public static void forEachSubDir(Path path, Stream<String> dirNames, Consumer<Path> pathConsumer) {
        dirNames.map(path::resolve).filter(Files::isDirectory).forEach(pathConsumer);
    }

    public static void forEachSubDir(Path path, Consumer<Path> pathConsumer) {
        try {
            Files.walk(path, 1).filter(file -> !file.equals(path)).filter(Files::isDirectory).forEach(pathConsumer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Stream<Path> findFiles(Path dir) {
        return find(dir, Files::isRegularFile);
    }

    public static Stream<Path> findDirs(Path dir) {
        return find(dir, Files::isDirectory);
    }

    public static Stream<Path> findFiles(Path dir, Predicate<Path> predicate) {
        return findFiles(dir).filter(predicate);
    }

    public static Stream<TextFile> findTextFiles(Path dir, String... suffixes) {
        return findFiles(dir).map(TextFile::new).filter(textFile -> textFile.hasSuffix(suffixes));
    }

    public static Stream<Path> findDirs(Path dir, Predicate<Path> predicate) {
        return find(dir, Files::isDirectory).filter(predicate);
    }

    public static class Builder<T extends Builder<T>> {
        T self() {
            return (T) this;
        }

        public List<String> opts = new ArrayList<>();

        public T opts(List<String> opts) {
            this.opts.addAll(opts);
            return self();
        }

        public T opts(String... opts) {
            opts(Arrays.asList(opts));
            return self();
        }

        public T basedOn(T stem) {
            if (stem != null) {
                opts.addAll(stem.opts);
            }
            return self();
        }

        public T when(boolean condition, Consumer<T> consumer) {
            if (condition) {
                consumer.accept(self());
            }
            return self();
        }

        public T either(boolean condition, Consumer<T> trueConsumer, Consumer<T> falseConsumer) {
            if (condition) {
                trueConsumer.accept(self());
            } else {
                falseConsumer.accept(self());
            }
            return self();
        }

    }

    public static class JavacBuilder extends Builder<JavacBuilder> {
        public Path classesDir;
        public List<Path> sourcePath;
        public List<Path> classPath;

        public JavacBuilder basedOn(JavacBuilder stem) {
            super.basedOn(stem);
            if (stem != null) {
                if (stem.classesDir != null) {
                    this.classesDir = stem.classesDir;
                }
                if (stem.sourcePath != null) {
                    this.sourcePath = new ArrayList<>(stem.sourcePath);
                }
                if (stem.classPath != null) {
                    this.classPath = new ArrayList<>(stem.classPath);
                }
            }
            return this;
        }

        public JavacBuilder classes_dir(Path classesDir) {
            this.classesDir = classesDir;
            return this;
        }

        public JavacBuilder source_path(Path... sourcePaths) {
            this.sourcePath = this.sourcePath==null?new ArrayList<>():this.sourcePath;
            this.sourcePath.addAll(Arrays.asList(sourcePaths));
            return this;
        }

        public JavacBuilder class_path(Path... classPaths) {
            this.classPath = this.classPath==null?new ArrayList<>():this.classPath;
            this.classPath.addAll(Arrays.asList(classPaths));
            return this;
        }
    }

    public static Stream<Path> find(Path dir) {
        try {
            return Files.walk(dir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Stream<Path> find(Path dir, Predicate<Path> predicate) {
        return find(dir).filter(predicate);
    }

    record RootAndPath(Path root, Path path) {
        Path relativize() {
            return root().relativize(path());
        }
    }

    public static JavacBuilder javac(JavacBuilder javacBuilder) {
        try {
            if (javacBuilder.classesDir == null) {
                javacBuilder.classesDir = Files.createTempDirectory("javacClasses");
                //   javacBuilder.classesDir = javacBuilder.jar.resolveSibling(javacBuilder.jar.getFileName().toString() + ".classes");
            }
            javacBuilder.opts.addAll(List.of("-d", javacBuilder.classesDir.toString()));
            mkdir(rmdir(javacBuilder.classesDir));

            if (javacBuilder.classPath != null) {
                javacBuilder.opts.addAll(List.of("--class-path", pathCharSeparated(javacBuilder.classPath)));
            }

            javacBuilder.opts.addAll(List.of("--source-path", pathCharSeparated(javacBuilder.sourcePath)));
            var src = new ArrayList<Path>();
            javacBuilder.sourcePath.forEach(entry -> findFiles(entry, file -> file.toString().endsWith(".java")).forEach(src::add));

            DiagnosticListener<JavaFileObject> dl = (diagnostic) -> {
                if (!diagnostic.getKind().equals(Diagnostic.Kind.NOTE)) {
                    System.out.println(diagnostic.getKind()
                            + " " + diagnostic.getLineNumber() + ":" + diagnostic.getColumnNumber() + " " + diagnostic.getMessage(null));
                }
            };

         //   List<RootAndPath> pathsToJar = new ArrayList<>();
            JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
            var compilationUnits = src.stream().map(path ->
                    new SimpleJavaFileObject(path.toUri(), JavaFileObject.Kind.SOURCE) {
                        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                            try {
                                return Files.readString(Path.of(toUri()));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }).toList();
            JavaCompiler.CompilationTask compilationTask = (javac.getTask(
                    new PrintWriter(System.err),
                    javac.getStandardFileManager(dl, null, null),
                    dl,
                    javacBuilder.opts,
                    null,
                    compilationUnits

            ));
            ((com.sun.source.util.JavacTask) compilationTask)
                    .generate();
                    //.forEach(fileObject -> pathsToJar.add(new RootAndPath(javacBuilder.classesDir, Path.of(fileObject.toUri()))));


            return javacBuilder;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static JavacBuilder javac(Consumer<JavacBuilder> javacBuilderConsumer) {
        JavacBuilder javacBuilder = new JavacBuilder();
        javacBuilderConsumer.accept(javacBuilder);
        return javac(javacBuilder);
    }

    public static class JarBuilder extends Builder<JarBuilder> {
        public Path jar;
        public List<Path> pathList;

        public JarBuilder basedOn(JarBuilder stem) {
            super.basedOn(stem);
            if (stem != null) {
                if (stem.jar != null) {
                    this.jar = stem.jar;
                }
                if (stem.pathList != null) {
                    this.pathList = new ArrayList<>(stem.pathList);
                }
            }
            return this;
        }

        public JarBuilder jar(Path jar) {
            this.jar = jar;
            return this;
        }

        public JarBuilder javac(Consumer<JavacBuilder> javacBuilderConsumer) {
            JavacBuilder javacBuilder = new JavacBuilder();
            javacBuilderConsumer.accept(javacBuilder);
            var result = Bldr.javac(javacBuilder);
            pathList = (pathList == null) ? new ArrayList<>() : pathList;
            pathList.add(result.classesDir);
            return this;
        }

        public JarBuilder path_list(Path... paths) {
            this.pathList = new ArrayList<>(Arrays.asList(paths));
            return this;
        }
    }

    public static JarBuilder jar(Consumer<JarBuilder> jarBuilderConsumer) {
        try {
            JarBuilder jarBuilder = new JarBuilder();
            jarBuilderConsumer.accept(jarBuilder);

            List<RootAndPath> pathsToJar = new ArrayList<>();
            var jarStream = new JarOutputStream(Files.newOutputStream(jarBuilder.jar));
            var setOfDirs = new HashSet<Path>();
            jarBuilder.pathList.stream().sorted().filter(Files::isDirectory).forEach(root ->
                    pathsToJar.addAll(findFiles(root).map(path -> new RootAndPath(root, path)).toList()));

            pathsToJar.stream().sorted(Comparator.comparing(RootAndPath::path)).forEach(rootAndPath -> {
                var parentDir = rootAndPath.path().getParent();
                try {
                    if (!setOfDirs.contains(parentDir)) {
                        setOfDirs.add(parentDir);
                        PosixFileAttributes attributes = Files.readAttributes(rootAndPath.path(), PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        var entry = new JarEntry(rootAndPath.relativize() + "/");
                        entry.setTime(attributes.lastModifiedTime().toMillis());
                        jarStream.putNextEntry(entry);
                        jarStream.closeEntry();
                    }
                    PosixFileAttributes attributes = Files.readAttributes(rootAndPath.path(), PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    var entry = new JarEntry(rootAndPath.relativize().toString());
                    entry.setTime(Files.getLastModifiedTime(rootAndPath.path()).toMillis());
                    jarStream.putNextEntry(entry);
                    if (attributes.isRegularFile()) {
                        Files.newInputStream(rootAndPath.path()).transferTo(jarStream);
                    }
                    jarStream.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            jarStream.finish();
            jarStream.close();
            return jarBuilder;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static class CMakeBuilder extends Builder<CMakeBuilder> {
        public List<String> libraries = new ArrayList<>();
        public Path buildDir;
        public Path sourceDir;
        // public Path cwd;
        private Path output;

        public CMakeBuilder() {
            opts.add("cmake");
        }

        public CMakeBuilder basedOn(CMakeBuilder stem) {
            // super.basedOn(stem); you will get two cmakes ;)
            if (stem != null) {
                if (stem.output != null) {
                    this.output = stem.output;
                }
                if (stem.libraries != null) {
                    this.libraries = new ArrayList<>(stem.libraries);
                }
                //  if (stem.cwd != null) {
                //    this.cwd = stem.cwd;
                // }
                if (stem.buildDir != null) {
                    this.buildDir = stem.buildDir;
                }
                if (stem.sourceDir != null) {
                    this.sourceDir = stem.sourceDir;
                }
            }
            return this;
        }

        public CMakeBuilder B(Path buildDir) {
            this.buildDir = buildDir;
            opts("-B", buildDir.toString());
            return this;
        }

        public CMakeBuilder S(Path sourceDir) {
            this.sourceDir = sourceDir;
            opts("-S", sourceDir.toString());
            return this;
        }

        public CMakeBuilder build(Path buildDir) {
            this.buildDir = buildDir;
            opts("--build", buildDir.toString());
            return this;
        }

        //  public CMakeBuilder cwd(Path cwd) {
        //     this.cwd = cwd;
        //    return this;
        // }


    }

    public static void cmake(Consumer<CMakeBuilder> cmakeBuilderConsumer) {

        CMakeBuilder cmakeBuilder = new CMakeBuilder();
        cmakeBuilderConsumer.accept(cmakeBuilder);
        try {
            Files.createDirectories(cmakeBuilder.buildDir);
            //System.out.println(cmakeConfig.opts);
            var cmakeProcessBuilder = new ProcessBuilder()
                    // .directory(cmakeBuilder.cwd.toFile())
                    .inheritIO()
                    .command(cmakeBuilder.opts)
                    .start();
            cmakeProcessBuilder.waitFor();
        } catch (InterruptedException ie) {
            System.out.println(ie);
        } catch (IOException ioe) {
            System.out.println(ioe);
        }
    }

    public static class JExtractBuilder extends Builder<JExtractBuilder> {
        public List<String> compileFlags = new ArrayList<>();
        public List<Path> libraries = new ArrayList<>();
        public List<Path> headers = new ArrayList<>();
        public Path cwd;

        public Path home;
        private String targetPackage;
        private Path output;

        public JExtractBuilder() {
            opts.add("jextract");
        }

        public JExtractBuilder basedOn(JExtractBuilder stem) {
            super.basedOn(stem);
            if (stem != null) {
                if (stem.output != null) {
                    this.output = stem.output;
                }
                if (stem.compileFlags != null) {
                    this.compileFlags = new ArrayList<>(stem.compileFlags);
                }
                if (stem.libraries != null) {
                    this.libraries = new ArrayList<>(stem.libraries);
                }
                if (stem.home != null) {
                    this.home = stem.home;
                }
                if (stem.cwd != null) {
                    this.cwd = stem.cwd;
                }
                if (stem.headers != null) {
                    this.headers = new ArrayList<>(stem.headers);
                }
            }
            return this;
        }


        public JExtractBuilder cwd(Path cwd) {
            this.cwd = cwd;
            return this;
        }

        public JExtractBuilder home(Path home) {
            this.home = home;
            opts.set(0, home.resolve("bin/jextract").toString());
            return this;
        }

        public JExtractBuilder opts(String... opts) {
            this.opts.addAll(Arrays.asList(opts));
            return this;
        }

        public JExtractBuilder target_package(String targetPackage) {
            this.targetPackage = targetPackage;
            opts("--target-package", targetPackage);
            return this;
        }

        public JExtractBuilder output(Path output) {
            this.output = output;
            opts("--output", output.toString());
            return this;
        }

        public JExtractBuilder library(Path... libraries) {
            this.libraries.addAll(Arrays.asList(libraries));
            for (Path library : libraries) {
                opts("--library", ":" + library);
            }
            return this;
        }

        public JExtractBuilder compile_flag(String... compileFlags) {
            this.compileFlags.addAll(Arrays.asList(compileFlags));
            return this;
        }

        public JExtractBuilder header(Path header) {
            this.headers.add(header);
            this.opts.add(header.toString());
            return this;
        }
    }


    static Path unzip(Path in, Path dir) {
        try {
            Files.createDirectories(dir);
            ZipFile zip = new ZipFile(in.toFile());
            zip.entries().asIterator().forEachRemaining(entry -> {
                try {
                    String currentEntry = entry.getName();

                    Path destFile = dir.resolve(currentEntry);
                    //destFile = new File(newPath, destFile.getName());
                    Path destinationParent = destFile.getParent();
                    Files.createDirectories(destinationParent);
                    // create the parent directory structure if needed


                    if (!entry.isDirectory()) {
                        zip.getInputStream(entry).transferTo(Files.newOutputStream(destFile));
                    }
                } catch (IOException ioe) {
                    throw new RuntimeException(ioe);
                }
            });
            zip.close();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return dir;
    }

    public static void jextract(Consumer<JExtractBuilder> jextractBuilderConsumer) {
        JExtractBuilder extractConfig = new JExtractBuilder();
        jextractBuilderConsumer.accept(extractConfig);
        System.out.println(extractConfig.opts);
        var compilerFlags = extractConfig.cwd.resolve("compiler_flags.txt");
        try {
            PrintWriter compilerFlagsWriter = new PrintWriter(Files.newOutputStream(compilerFlags));
            compilerFlagsWriter.println(extractConfig.compileFlags);
            compilerFlagsWriter.close();

            Files.createDirectories(extractConfig.output);
            var jextractProcessBuilder = new ProcessBuilder()
                    .directory(extractConfig.cwd.toFile())
                    .inheritIO()
                    .command(extractConfig.opts)
                    .start();
            jextractProcessBuilder.waitFor();
            Files.deleteIfExists(compilerFlags);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException ie) {
            System.out.println(ie);
        }
    }

    public static Path mkdir(Path path) {
        try {
            return Files.createDirectories(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public record TextFile(Path path) {
        public Stream<Line> lines() {
            try {
                int num[] = new int[]{1};
                return Files.readAllLines(path(), StandardCharsets.UTF_8).stream().map(line -> new Line(line, num[0]++));
            } catch (IOException ioe) {
                System.out.println(ioe);
                return new ArrayList<Line>().stream();
            }
        }

        public boolean grep(Pattern pattern) {
            return lines().anyMatch(line -> pattern.matcher(line.line).matches());
        }

        public boolean hasSuffix(String... suffixes) {
            var suffixSet = Set.of(suffixes);
            int dotIndex = path().toString().lastIndexOf('.');
            return dotIndex == -1 || suffixSet.contains(path().toString().substring(dotIndex + 1));
        }
    }

    public record Line(String line, int num) {
        public boolean grep(Pattern pattern) {
            return pattern.matcher(line()).matches();
        }
    }

    public enum Scope {
        TEST, COMPILE, PROVIDED, RUNTIME, SYSTEM;

        static Scope of(String name) {
            return switch (name.toLowerCase()) {
                case "test" -> TEST;
                case "compile" -> COMPILE;
                case "provided" -> PROVIDED;
                case "runtime" -> RUNTIME;
                case "system" -> SYSTEM;
                default -> COMPILE;
            };
        }
    }

    public record Version(int maj, int min, int point, String modifier) {
        public String spec() {
            StringBuilder stringBuilder = new StringBuilder();
            if (maj >= 0) {
                stringBuilder.append(maj);
                if (min >= 0) {
                    stringBuilder.append(".").append(min);
                    if (point >= 0) {
                        stringBuilder.append(".").append(point);
                        if (modifier != null && !modifier.isEmpty()) {
                            stringBuilder.append("-").append(modifier);
                        }
                    }
                }
            } else {
                stringBuilder.append(1);
            }
            return stringBuilder.toString();
        }

        record Spec(int maj, int min, int point, String modifier) {
        }

        static Pattern IntPrefixPattern = Pattern.compile("^\\.?([0-9]+)(.*)$");

        static Spec parse(String spec) {

            if (spec.isEmpty()) {
                return new Spec(-1, -1, -1, null);
            } else {
                var majMatch = IntPrefixPattern.matcher(spec);
                if (majMatch.matches()) {
                    int maj = Integer.parseInt(majMatch.group(1));
                    var minMatch = IntPrefixPattern.matcher(majMatch.group(2));
                    if (minMatch.matches()) {
                        int min = Integer.parseInt(minMatch.group(1));
                        var pointMatch = IntPrefixPattern.matcher(minMatch.group(2));
                        if (pointMatch.matches()) {
                            int point = Integer.parseInt(pointMatch.group(1));
                            return new Spec(maj, min, point, pointMatch.group(2));
                        } else {
                            return new Spec(maj, min, -1, null);
                        }
                    } else {
                        return new Spec(maj, -1, -1, null);
                    }
                } else {
                    throw new IllegalArgumentException("Invalid spec: " + spec);
                }
            }

            //var matcher = Pattern.compile("^([0-9]*)\\.([0-9]*)\\.([0-9]*)(.*)$").matcher(spec);
            // return new Spec(1,-1,-1,"");
        }

        Version() {
            this(-1, -1, -1, null);
        }

        Version(int maj) {
            this(maj, -1, -1, null);
        }

        Version(int maj, int min) {
            this(maj, min, -1, null);
        }

        Version(int maj, int min, int point) {
            this(maj, min, point, null);
        }


        Version(String spec) {
            this(parse(spec));
        }

        Version(Spec spec) {
            this(spec.maj, spec.min, spec.point, spec.modifier);
        }
    }

    public record Artifact(Path dir, String groupId, String artifactId, Version version, Scope scope,
                           boolean optional) {
        static final String MAVEN_REPO = "https://repo.maven.apache.org/maven2/";

        static String groupId(XMLNode xmlNode) {
            return xmlNode.xpathQueryString("groupId/text()");
        }

        static String artifactId(XMLNode xmlNode) {
            return xmlNode.xpathQueryString("artifactId/text()");
        }

        static String versionId(XMLNode xmlNode) {
            return xmlNode.xpathQueryString("versionId/text()");
        }

        Artifact(Path dir, XMLNode xmlNode) {
            this(dir,
                    groupId(xmlNode), artifactId(xmlNode),
                    new Version(versionId(xmlNode)),
                    Scope.of(xmlNode.xpathQueryString("scope/text()")),
                    Boolean.parseBoolean(xmlNode.xpathQueryString("optional/text()"))
            );
        }

        public Artifact(Path dir, String group, String artifact, Version version) {
            this(dir, group, artifact, version, Scope.COMPILE, false);
        }

        public String artifactAndVersion() {
            return artifactId() + '-' + version().spec();
        }

        public String pathName() {
            return groupId() + '.' + artifactAndVersion();
        }

        private String location() {
            return MAVEN_REPO + groupId().replace('.', '/') + "/" + artifactId() + "/" + version().spec();
        }

        private String name(String suffix) {
            return artifactAndVersion() + "." + suffix;
        }

        public Path pomPath() {
            return dir.resolve(name("pom"));
        }

        public URL url(String suffix) {
            try {
                return new URI(location() + "/" + name(suffix)).toURL();
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        }

        public URL pomURL() {
            return url("pom");
        }

        public URL jarURL() {
            return url("jar");
        }


        public Path jarPath() {
            return dir.resolve(name("jar"));
        }

        public Artifact download() {
            if (isRegularFile(pomPath())) {
                println("We already have " + pomPath());
            } else {
                println("Downloading " + pomPath() + " and " + jarPath());
                try {
                    pomURL().openStream().transferTo(Files.newOutputStream(pomPath()));
                    jarURL().openStream().transferTo(Files.newOutputStream(jarPath()));
                    dependencies();
                } catch (IOException e) {
                    if (version.maj() == -1) {
                        Artifact artifact = new Artifact(this.dir, this.groupId, this.artifactId, new Version(1));
                        artifact.download();
                    } else if (version.min() == -1) {
                        Artifact artifact = new Artifact(this.dir, this.groupId, this.artifactId, new Version(version.maj, 0));
                        artifact.download();
                    } else {
                        throw new RuntimeException(e);
                    }
                }
            }
            return this;
        }

        public XMLNode pomXML() {
            try {
                return new XMLNode(dir.resolve(name("pom")));
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }

        public List<Artifact> dependencies() {
            List<Artifact> artifacts = new ArrayList<>();
            var xmlNode = pomXML();
            var nodeList = xmlNode.nodeList(xmlNode.xpath("/project/dependencies/dependency"));
            for (int i = 0; i < nodeList.getLength(); i++) {
                var node = nodeList.item(i);
                var dependency = new Artifact(dir, new XMLNode((Element) node));
                // if (!Files.exists(dependency.pomPath())){
                dependency.download();
                /// }
                if (dependency.optional()) {
                    println(dependency + " is optional");
                } else if (dependency.scope.equals(Scope.COMPILE)) {
                    artifacts.add(dependency);
                    artifacts.addAll(dependency.dependencies());
                } else {
                    println("skipping " + dependency);
                }
            }
            return artifacts;
        }

    }

    public static Path curl(URL url, Path file) {
        try {
            println("Downloading " + url + "->" + file);
            url.openStream().transferTo(Files.newOutputStream(file));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return file;
    }

    public static Optional<Path> which(String execName) {
        // which and whereis had issues.
        return Arrays.asList(System.getenv("PATH").split(File.pathSeparator)).stream()
                .map(dirName -> Path.of(dirName).resolve(execName).normalize())
                .filter(Files::isExecutable).findFirst();
    }

    public static boolean canExecute(String execName) {
        // which and whereis had issues.
        return which(execName).isPresent();
    }

    public static Path untar(Path tarFile, Path dir) {
        try {
            new ProcessBuilder().inheritIO().command("tar", "xvf", tarFile.toString(), "--directory", tarFile.getParent().toString()).start().waitFor();
            return dir;
        } catch (
                InterruptedException e) { // We get IOException if the executable not found, at least on Mac so interuppted means it exists
            return null;
        } catch (IOException e) { // We get IOException if the executable not found, at least on Mac
            //throw new RuntimeException(e);
            return null;
        }
    }


    public static Matcher pathMatcher(Path path, Pattern pattern) {
        return pattern.matcher(path.toString());
    }

    public static boolean matches(Path path, Pattern pattern) {
        return pathMatcher(path, pattern).matches();
    }

    public static boolean matches(Path path, String pattern) {
        return pathMatcher(path, Pattern.compile(pattern)).matches();
    }

    public static boolean failsAndMatches(Path path, String failMe, String passMe) {
        return !matches(path, failMe) && matches(path, Pattern.compile(passMe));
    }

    public static Artifact artifact(Path path, String group, String artifact, Version version) {
        return new Artifact(path, group, artifact, version);
    }


    public record Project(Path targetDir, Path path,
                          String variant) implements TargetDirProvider, JavaSourceDirProvider, ResourceDirProvider {


        public Path javaSourceDir() {
            return path().resolve("src/main/java");
        }

        public Path resourcesDir() {
            return path().resolve("src/main/resources");
        }

        public String prefixNameVariantSuffix(String prefix, String suffix) {
            return (prefix.isEmpty() ? "" : prefix + "-") + path().getFileName() + "-" + variant() + suffix;
        }

        public Path target(String prefix, String suffix) {
            return targetDir.resolve(prefixNameVariantSuffix(prefix, suffix));
        }

        public JarBuilder build(String prefix, JavacBuilder javacBuilder) {
            println("Building  " + path().getFileName() + "-" + variant());
            return jar($ -> $
                    .jar(target(prefix, ".jar"))
                    .when(isDirectory(resourcesDir()), $$ -> $$.path_list(resourcesDir()))
                    .javac($$ -> $$.basedOn(javacBuilder)
                            .classes_dir(target(prefix, ".jar.classes"))
                            .source_path(javaSourceDir())
                    )
            );
        }

        public JarBuilder build(JavacBuilder javacBuilder) {
            return build("", javacBuilder);
        }
    }

    public static boolean withOptionalDirectory(Path dir, Consumer<Path> pathConsumer) {
        if (isDirectory(dir)) {
            pathConsumer.accept(dir);
            return true;
        } else {
            return false;
        }
    }

    public static void withExpectedDirectory(Path dir, Consumer<Path> pathConsumer) {
        if (isDirectory(dir)) {
            pathConsumer.accept(dir);
        } else {
            throw new IllegalStateException("Failed to find directory " + dir);
        }
    }

    public static void withExpectedDirectory(Path parent, String subDirName, Consumer<Path> pathConsumer) {
        withExpectedDirectory(parent.resolve(subDirName), pathConsumer);
    }

    public static boolean withOptionalDirectory(Path parent, String subDirName, Consumer<Path> pathConsumer) {
        return withOptionalDirectory(parent.resolve(subDirName), pathConsumer);
    }

    public record Root(Path path) {
        public Path buildDir() {
            return mkdir(path.resolve("build"));
        }

        public Path thirdPartyDir() {
            return mkdir(path.resolve("thirdparty"));
        }

        public Path repoDir() {
            return mkdir(path.resolve("repoDir"));
        }

        public Root() {
            this(Path.of(System.getProperty("user.dir")));
        }

        public Repo repo() {
            return new Repo(repoDir());
        }


        public Path requireJExtract() {
            var optional = executablesInPath("jextract").findFirst();
            if (optional.isPresent()) {
                println("Found jextract in PATH");
                return optional.get().getParent().getParent(); // we want the 'HOME' dir
            }
            println("No jextract in PATH");
            URL downloadURL = null;
            var extractVersionMaj = "22";
            var extractVersionMin = "5";
            var extractVersionPoint = "33";
            try {
                downloadURL = new URI("https://download.java.net/java/early_access"
                        + "/jextract/" + extractVersionMaj + "/" + extractVersionMin
                        + "/openjdk-" + extractVersionMaj + "-jextract+" + extractVersionMin + "-" + extractVersionPoint + "_"
                        + os.nameArchTuple() + "_bin.tar.gz").toURL();
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
            URL finalDownloadURL = downloadURL;

            println("... attempting download from" + downloadURL);
            var jextractTar = thirdPartyDir().resolve("jextract.tar");

            if (!isRegularFile(jextractTar)) { // Have we downloaded already?
                jextractTar = curl(finalDownloadURL, jextractTar); // if not
            }

            var jextractHome = thirdPartyDir().resolve("jextract-22");
            if (!isDirectory(jextractHome)) {
                untar(jextractTar, jextractHome);
            }
            return jextractHome;

        }
    }

    public static Stream<Path> executablesInPath(String name) {
        return Arrays.asList(System.getenv("PATH").split(File.pathSeparator)).stream()
                .map(dirName -> Path.of(dirName).resolve(name).normalize())
                .filter(Files::isExecutable);

    }

    public static void sanity(Root hatDir) {
        var rleParserDir = hatDir.path().resolve("examples/life/src/main/java/io");
        subDirStream(hatDir.path(), "hat", "examples", "backends", "docs").forEach(dir ->
                findTextFiles(dir, "java", "cpp", "h", "hpp", "md")
                        .forEach(textFile -> {
                            if (!textFile.hasSuffix("md")
                                    && !textFile.path().startsWith(rleParserDir)
                                    && !textFile.grep(Pattern.compile("^.*Copyright.*202[4-9].*(Intel|Oracle).*$"))) {
                                System.err.println("ERR MISSING LICENSE " + textFile.path());
                            }
                            textFile.lines().forEach(line -> {
                                if (line.grep(Pattern.compile("^.*\\t.*"))) {
                                    System.err.println("ERR TAB " + textFile.path() + ":" + line.line() + "#" + line.num());
                                }
                                if (line.grep(Pattern.compile("^.* $"))) {
                                    System.err.println("ERR TRAILING WHITESPACE " + textFile.path() + ":" + line.line() + "#" + line.num());
                                }
                            });
                        })
        );
    }

    //  https://stackoverflow.com/questions/23272861/how-to-call-testng-xml-from-java-main-method
    public static void main(String[] args) throws Throwable {
        var hatDir = new Root(Path.of("/Users/grfrost/github/babylon-grfrost-fork/hat"));
        // println(which("java")+"?");
        //  System.exit(1);

        //repo.versions("org.testng", "testng").forEach(s->println(s));

        println(hatDir.repo().forEachVersion("org.testng", "testng", version -> println(version)));

        println(hatDir.repo().forEachId("org.testng", "testng", id -> println(id)));
        //   var testng = artifact(thirdPartyDir,"org.testng", "testng", new Version(7,1,0));
        //   testng.download().dependencies();
        // g.dependencies().stream().forEach(artifactId->println(artifactId.artifactAndVersion()));
        // println(g.pathName());

        //  Artifact aparapi = Artifact.of("com.aparapi", "aparapi", "3.0.2").download(thirdPartyDir);
        //  Artifact aparapi_jni = Artifact.of("com.aparapi", "aparapi-jni", "1.4.3").download(thirdPartyDir);
        //  Artifact aparapi_examples = Artifact.of("com.aparapi", "aparapi-examples", "3.0.0").download(thirdPartyDir);
        //  RepoPom testng = repo.get("org.testng", "testng", "7.1.0");

        //  var url = new URI("https://repo1.maven.org/maven2/org/testng/testng/7.1.0/testng-7.1.0.pom").toURL();
        //  var node = new XMLNode(url);
        //  RepoPom testng = new RepoPom(new XMLNode(new URI("https://repo1.maven.org/maven2/org/testng/testng/7.1.0/testng-7.1.0.pom").toURL()));
        //  testng.downloadTo(repo.dir, "jar");
        // testng.dependencies().stream().forEach(dependency->println(dependency.groupId()));
        // testng.dependencies().stream().forEach(dependency->println(dependency.pomURL()));

        // https://repo1.maven.org/maven2/org/testng/testng/7.1.0/testng-7.1.0.jar
        // var hatDir = path("/Users/grfrost/github/babylon-grfrost-fork/hat");

        sanity(hatDir);

        withExpectedDirectory(hatDir.path(), "hat", hatProjectDir -> {
            var hatJavacOpts = new JavacBuilder().opts(
                    "--source", "24",
                    "--enable-preview",
                    "--add-exports=java.base/jdk.internal=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.vm.annotation=ALL-UNNAMED"
            );

            var hatJarResult = new Project(hatDir.buildDir(), hatProjectDir, "1.0").build(hatJavacOpts);

            var hatExampleJavacConfig = new JavacBuilder().basedOn(hatJavacOpts).class_path(hatJarResult.jar);

            withExpectedDirectory(hatDir.path(), "backends", backendsDir -> {
                subDirStream(backendsDir, "opencl", "ptx")
                        .map(backendDir -> new Project(hatDir.buildDir(), backendDir, "1.0"))
                        .parallel()
                        .forEach(project -> project.build("hat-backend", hatExampleJavacConfig));

                var cmakeBuildDir = hatDir.buildDir().resolve("cmake-build-debug");

                if (!isDirectory(cmakeBuildDir)) { // We need to rerun build -B defaultCMakeBuilder.buildDir
                    mkdir(cmakeBuildDir);
                    cmake($ -> $
                            .S(backendsDir)
                            .B(cmakeBuildDir)
                            .opts("-DHAT_TARGET=" + hatDir.buildDir())
                    );
                }

                cmake($ -> $
                        // .S(backendsDir)
                        .build(cmakeBuildDir)
                );
            });

            withExpectedDirectory(hatDir.path, "examples", examplesDir ->
                    subDirStream(examplesDir, "blackscholes", "mandel", "squares", "heal", "violajones", "life")
                            .map(exampleDir -> new Project(hatDir.buildDir(), exampleDir, "1.0"))
                            .parallel()
                            .forEach(project -> project.build("hat-example", hatExampleJavacConfig))
            );

            withOptionalDirectory(hatDir.path, "hattricks", hattricksDir -> {
                subDirStream(hattricksDir, "chess", "view")
                        .map(hattrickDir -> new Project(hatDir.buildDir(), hattrickDir, "1.0"))
                        .parallel()
                        .forEach(project -> project.build("hat-example", hatExampleJavacConfig));


                withOptionalDirectory(hattricksDir, "nbody", nbody -> {
                    var jextractedJava = mkdir(hatDir.buildDir().resolve("jextracted-java"));
                    var extractedOpenCLCode = jextractedJava.resolve("opencl");
                    if (!isDirectory(extractedOpenCLCode)) {
                        mkdir(extractedOpenCLCode);
                        jextract($$ -> $$
                                .home(hatDir.requireJExtract())
                                .cwd(nbody)
                                .output(jextractedJava)
                                .target_package("opencl")
                                .when(os.isMac(), $$$ -> $$$
                                        .compile_flag("-F" + os.macAppLibFrameworks())
                                        .library(os.macFramework("OpenCL.framework/OpenCL"))
                                        .header(os.macFrameworkHeaderFile("OpenCL.framework/Headers/opencl.h"))
                                )
                        );
                    }
                    var extractedOpenGLCode = jextractedJava.resolve("opengl");
                    if (!isDirectory(extractedOpenGLCode)) {
                        mkdir(extractedOpenGLCode);
                        jextract($$ -> $$
                                .home(hatDir.requireJExtract())
                                .cwd(nbody)
                                .output(jextractedJava)
                                .target_package("opengl")
                                .when(os.isMac(), $$$ -> $$$
                                        .compile_flag("-F" + os.macAppLibFrameworks())
                                        .library(
                                                os.macFramework("GLUT.framework/GLUT"),
                                                os.macFramework("OpenGL.framework/OpenGL")
                                        )
                                        .header(os.macFrameworkHeaderFile("GLUT.framework/Headers/glut.h"))
                                )
                        );
                    }

                    jar($ -> $
                            .jar(hatDir.buildDir().resolve("hat-example-nbody-1.0.jar"))
                            .path_list(nbody.resolve("src/main/resources"))
                            .javac($$ -> $$.basedOn(hatExampleJavacConfig)
                                    .source_path(nbody.resolve("src/main/java"), extractedOpenCLCode, extractedOpenGLCode)
                            )
                    );
                });
            });
        });
    }
}
