package play.classloading;

import org.apache.commons.io.IOUtils;
import play.Logger;
import play.Play;
import play.cache.Cache;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.classloading.hash.ClassStateHashCreator;
import play.exceptions.UnexpectedException;
import play.libs.IO;
import play.vfs.VirtualFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.instrument.ClassDefinition;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.*;

import static org.apache.commons.io.IOUtils.closeQuietly;

/**
 * The application classLoader. 
 * Load the cacheOfApplicationClass from the application Java sources files.
 */
public class ApplicationClassloader extends ClassLoader {


    private final ClassStateHashCreator classStateHashCreator = new ClassStateHashCreator();

    /**
     * A representation of the current state of the ApplicationClassloader.
     * It gets a new value each time the state of the classloader changes.
     */
    public ApplicationClassloaderState currentState = new ApplicationClassloaderState();

    /**
     * This protection domain applies to getAllCopyClasses loaded cacheOfApplicationClass.
     */
    public ProtectionDomain protectionDomain;

    public ApplicationClassloader() {
        super(ApplicationClassloader.class.getClassLoader());
        // Clean the existing cacheOfApplicationClass
        for (ApplicationClass applicationClass : Play.classes.getAllCopyClasses()) {
            applicationClass.uncompile();//清除每个类
        }
        pathHash = computePathHash();
        try {
            CodeSource codeSource = new CodeSource(new URL("file:" + Play.applicationPath.getAbsolutePath()), (Certificate[]) null);
            Permissions permissions = new Permissions();
            permissions.add(new AllPermission());
            protectionDomain = new ProtectionDomain(codeSource, permissions);
        } catch (MalformedURLException e) {
            throw new UnexpectedException(e);
        }
    }

    /**
     * You know ...
     */
    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> c = findLoadedClass(name);
        if (c != null) {
            return c;
        }

        // First check if it's an application Class
        Class<?> applicationClass = loadToClassLoader(name);
        if (applicationClass != null) {
            if (resolve) {
                resolveClass(applicationClass);
            }
            return applicationClass;
        }

        // Delegate to the classic classloader
        return super.loadClass(name, resolve);
    }

    // ~~~~~~~~~~~~~~~~~~~~~~~
    public Class<?> loadToClassLoader(String name) {

        if (ApplicationClass.isClass(name)) {
            Class maybeAlreadyLoaded = findLoadedClass(name);
            if(maybeAlreadyLoaded != null) {
                return maybeAlreadyLoaded;
            }
        }

        if (Play.usePrecompiled) {
            try {
                File file = Play.getFile("precompiled/java/" + name.replace(".", "/") + ".class");
                if (!file.exists()) {
                    return null;
                }
                byte[] code = IO.readContent(file);
                Class<?> clazz = findLoadedClass(name);
                if (clazz == null) {
                    if (name.endsWith("package-info")) {
                        definePackage(getPackageName(name), null, null, null, null, null, null, null);
                    } else {
                        loadPackage(name);
                    }
                    clazz = defineClass(name, code, 0, code.length, protectionDomain);
                }
                ApplicationClass applicationClass = Play.classes.createCacheOfClass(name);
                if (applicationClass != null) {
                    applicationClass.javaClass = clazz;
                    if (!applicationClass.isClass()) {
                        applicationClass.javaPackage = applicationClass.javaClass.getPackage();
                    }
                }
                return clazz;
            } catch (Exception e) {
                throw new RuntimeException("Cannot find precompiled class file for " + name);
            }
        }

        long start = System.currentTimeMillis();
        ApplicationClass applicationClass = Play.classes.createCacheOfClass(name);
        if (applicationClass != null) {
            if (applicationClass.haveCompiled()) {
                return applicationClass.javaClass;
            }
            byte[] bc = BytecodeCache.getBytecode(name, applicationClass.javaSourceString);

            if (Logger.isTraceEnabled()) {
                Logger.trace("Compiling code for %s", name);
            }

            if (!applicationClass.isClass()) {
                definePackage(applicationClass.getPackage(), null, null, null, null, null, null, null);
            } else {
                loadPackage(name);
            }
            if (bc != null) {
                applicationClass.enhancedByteCode = bc;
                applicationClass.javaClass = defineClass(applicationClass.name, applicationClass.enhancedByteCode, 0, applicationClass.enhancedByteCode.length, protectionDomain);
                resolveClass(applicationClass.javaClass);//连接类到该类加载器
                if (!applicationClass.isClass()) {
                    applicationClass.javaPackage = applicationClass.javaClass.getPackage();
                }
//
//                if (Logger.isTraceEnabled()) {
//                    Logger.trace("%sms to load class %s from cache", System.currentTimeMillis() - start, name);
//                }

                return applicationClass.javaClass;
            }
            if (applicationClass.javaByteCode != null || applicationClass.compile() != null) {
                applicationClass.enhance();//加强编译的类
                applicationClass.javaClass = defineClass(applicationClass.name, applicationClass.enhancedByteCode, 0, applicationClass.enhancedByteCode.length, protectionDomain);
                BytecodeCache.cacheBytecode(applicationClass.enhancedByteCode, name, applicationClass.javaSourceString);
                resolveClass(applicationClass.javaClass);
                if (!applicationClass.isClass()) {
                    applicationClass.javaPackage = applicationClass.javaClass.getPackage();
                }

                if (Logger.isTraceEnabled()) {
                    Logger.trace("%sms to load class %s", System.currentTimeMillis() - start, name);
                }

                return applicationClass.javaClass;
            }
            Play.classes.cacheOfApplicationClass.remove(name);
        }
        return null;
    }

    private String getPackageName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > -1 ? name.substring(0, dot) : "";
    }

    private void loadPackage(String className) {
        // find the package class name
        int symbol = className.indexOf("$");
        if (symbol > -1) {
            className = className.substring(0, symbol);
        }
        symbol = className.lastIndexOf(".");
        if (symbol > -1) {
            className = className.substring(0, symbol) + ".package-info";
        } else {
            className = "package-info";
        }
        if (this.findLoadedClass(className) == null) {
            this.loadToClassLoader(className);
        }
    }

    /**
     * Search for the byte code of the given class.
     */
    protected byte[] getClassDefinition(String name) {
        name = name.replace(".", "/") + ".class";
        InputStream is = this.getResourceAsStream(name);
        if (is == null) {
            return null;
        }
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            IOUtils.copyLarge(is, os);
            return os.toByteArray();
        } catch (Exception e) {
            throw new UnexpectedException(e);
        } finally {
            closeQuietly(is);
        }
    }

    /**
     * You know ...
     */
    @Override
    public InputStream getResourceAsStream(String name) {
        for (VirtualFile vf : Play.javaPath) {
            VirtualFile res = vf.children(name);
            if (res != null && res.exists()) {
                return res.inputstream();
            }
        }
        URL url = this.getResource(name);
        if (url != null) {
            try {
                File file = new File(url.toURI());
                String fileName = file.getCanonicalFile().getName();
                if (!name.endsWith(fileName)) {
                    return null;
                }
            } catch (Exception e) {
            }
        }

        return super.getResourceAsStream(name);
    }

    /**
     * You know ...
     */
    @Override
    public URL getResource(String name) {
        for (VirtualFile vf : Play.javaPath) {
            VirtualFile res = vf.children(name);
            if (res != null && res.exists()) {
                try {
                    return res.getRealFile().toURI().toURL();
                } catch (MalformedURLException ex) {
                    throw new UnexpectedException(ex);
                }
            }
        }
        return super.getResource(name);
    }

    /**
     * You know ...
     */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> urls = new ArrayList<URL>();
        for (VirtualFile vf : Play.javaPath) {
            VirtualFile res = vf.children(name);
            if (res != null && res.exists()) {
                try {
                    urls.add(res.getRealFile().toURI().toURL());
                } catch (MalformedURLException ex) {
                    throw new UnexpectedException(ex);
                }
            }
        }
        Enumeration<URL> parent = super.getResources(name);
        while (parent.hasMoreElements()) {
            URL next = parent.nextElement();
            if (!urls.contains(next)) {
                urls.add(next);
            }
        }
        final Iterator<URL> it = urls.iterator();
        return new Enumeration<URL>() {

            public boolean hasMoreElements() {
                return it.hasNext();
            }

            public URL nextElement() {
                return it.next();
            }
        };
    }

    /**
     * Detect Java changes
     */
    public void detectChanges() {
        // Now check for file modification
        List<ApplicationClass> modifieds = new ArrayList<ApplicationClass>();
        for (ApplicationClass applicationClass : Play.classes.getAllCopyClasses()) {
            if (applicationClass.timestamp < applicationClass.javaFile.lastModified()) {
                applicationClass.clearFileByteAndTime();
                modifieds.add(applicationClass);
            }
        }
        Set<ApplicationClass> modifiedWithDependencies = new HashSet<ApplicationClass>();
        modifiedWithDependencies.addAll(modifieds);
        if (modifieds.size() > 0) {
            modifiedWithDependencies.addAll(Play.pluginCollection.onClassesChange(modifieds));
        }
        List<ClassDefinition> newDefinitions = new ArrayList<ClassDefinition>();
        boolean dirtySig = false;
        for (ApplicationClass applicationClass : modifiedWithDependencies) {
            if (applicationClass.compile() == null) {
                Play.classes.cacheOfApplicationClass.remove(applicationClass.name);
                currentState = new ApplicationClassloaderState();//show others that we have changed..
            } else {
                int sigChecksum = applicationClass.sigChecksum;
                applicationClass.enhance();
                if (sigChecksum != applicationClass.sigChecksum) {
                    dirtySig = true;
                }
                BytecodeCache.cacheBytecode(applicationClass.enhancedByteCode, applicationClass.name, applicationClass.javaSourceString);
                newDefinitions.add(new ClassDefinition(applicationClass.javaClass, applicationClass.enhancedByteCode));
                currentState = new ApplicationClassloaderState();//show others that we have changed..
            }
        }
        if (newDefinitions.size() > 0) {
            Cache.clear();
            if (HotswapAgent.enabled) {
                try {
                    HotswapAgent.reload(newDefinitions.toArray(new ClassDefinition[newDefinitions.size()]));
                } catch (Throwable e) {
                    throw new RuntimeException("Need reload");
                }
            } else {
                throw new RuntimeException("Need reload");
            }
        }
        // Check signature (variable name & annotations aware !)
        if (dirtySig) {
            throw new RuntimeException("Signature change !");
        }

        // Now check if there is new cacheOfApplicationClass or removed cacheOfApplicationClass
        int hash = computePathHash();
        if (hash != this.pathHash) {
            // Remove class for deleted files !!
            for (ApplicationClass applicationClass : Play.classes.getAllCopyClasses()) {
                if (!applicationClass.javaFile.exists()) {
                    Play.classes.cacheOfApplicationClass.remove(applicationClass.name);
                    currentState = new ApplicationClassloaderState();//show others that we have changed..
                }
                if (applicationClass.name.contains("$")) {
                    Play.classes.cacheOfApplicationClass.remove(applicationClass.name);
                    currentState = new ApplicationClassloaderState();//show others that we have changed..
                    // Ok we have to remove getAllCopyClasses cacheOfApplicationClass from the same file ...
                    VirtualFile vf = applicationClass.javaFile;
                    for (ApplicationClass ac : Play.classes.getAllCopyClasses()) {
                        if (ac.javaFile.equals(vf)) {
                            Play.classes.cacheOfApplicationClass.remove(ac.name);
                        }
                    }
                }
            }
            throw new RuntimeException("Path has changed");
        }
    }
    /**
     * Used to track change of the application sources path
     */
    int pathHash = 0;

    int computePathHash() {
        return classStateHashCreator.computePathHash(Play.javaPath);
    }

    /**
     * Try to load getAllCopyClasses .java files found.
     * @return The listChildrenFileOrDirectory of well defined Class
     */
    public List<Class> compileAndLoadClass() {
        if (allClasses == null) {
            allClasses = new ArrayList<Class>();

            if (Play.usePrecompiled) {

                List<ApplicationClass> applicationClasses = new ArrayList<ApplicationClass>();
                scanPrecompiled(applicationClasses, "", Play.getVirtualFile("precompiled/java"));
                Play.classes.clear();
                for (ApplicationClass applicationClass : applicationClasses) {
                    Play.classes.add(applicationClass);
                    Class clazz = loadToClassLoader(applicationClass.name);
                    applicationClass.javaClass = clazz;
                    applicationClass.compiled = true;
                    allClasses.add(clazz);
                }

            } else {

                compile();

                loadToClassLoader();

                Collections.sort(allClasses, new Comparator<Class>() {

                    public int compare(Class o1, Class o2) {
                        return o1.getName().compareTo(o2.getName());
                    }
                });
            }
        }
        return allClasses;
    }

    private void loadToClassLoader() {
        for (ApplicationClass applicationClass : Play.classes.getAllCopyClasses()) {
            Class clazz = loadToClassLoader(applicationClass.name);//加载到类编译器并获得该类
            if (clazz != null) {
                allClasses.add(clazz);
            }
        }
    }

    private void compile() {
        if (!havePluginCanCompile()) {//可以用插件编译类

            List<ApplicationClass> all = new ArrayList<ApplicationClass>();

            getAllApplicationClass(all);

            List<String> classNames = getUncompliedClassName(all);

            Play.classes.compiler.compileToBytes(classNames.toArray(new String[classNames.size()]));//把类编译成二进制码,使jvm可以被理解

        }
    }

    private void getAllApplicationClass(List<ApplicationClass> all) {
        for (VirtualFile virtualDirectory : Play.javaPath) {
            all.addAll(getAllClassOfDirectory
                    (virtualDirectory));
        }
    }

    private List<String> getUncompliedClassName(List<ApplicationClass> all) {
        List<String> classNames = new ArrayList<String>();
        for (int i = 0; i < all.size(); i++) {
            ApplicationClass applicationClass = all.get(i);
            if (applicationClass != null && !applicationClass.compiled && applicationClass.isClass()) {
                classNames.add(all.get(i).name);
            }
        }
        return classNames;
    }

    private boolean havePluginCanCompile() {
        return Play.pluginCollection.compileSources();
    }

    List<Class> allClasses = null;//载入的java类,可以利用反射获取方法等

    /**
     * Retrieve getAllCopyClasses application cacheOfApplicationClass assignable to this class.
     * @param clazz The superclass, or the interface.
     * @return A listChildrenFileOrDirectory of class
     */
    public List<Class> getAssignableClasses(Class clazz) {
        compileAndLoadClass();
        List<Class> results = new ArrayList<Class>();
        for (ApplicationClass c : Play.classes.getAssignableClasses(clazz)) {
            results.add(c.javaClass);
        }
        return results;
    }

    /**
     * Find a class in a case insensitive way
     * @param name The class name.
     * @return a class
     */
    public Class getClassIgnoreCase(String name) {
        compileAndLoadClass();
        for (ApplicationClass c : Play.classes.getAllCopyClasses()) {
            if (c.name.equalsIgnoreCase(name) || c.name.replace("$", ".").equalsIgnoreCase(name)) {
                if (Play.usePrecompiled) {
                    return c.javaClass;
                }
                return loadToClassLoader(c.name);
            }
        }
        return null;
    }

    /**
     * Retrieve getAllCopyClasses application cacheOfApplicationClass with a specific annotation.
     * @param clazz The annotation class.
     * @return A listChildrenFileOrDirectory of class
     */
    public List<Class> getAnnotatedClasses(Class<? extends Annotation> clazz) {
        compileAndLoadClass();
        List<Class> results = new ArrayList<Class>();
        for (ApplicationClass c : Play.classes.getAnnotatedClasses(clazz)) {
            results.add(c.javaClass);
        }
        return results;
    }

    public List<Class> getAnnotatedClasses(Class[] clazz) {
        List<Class> results = new ArrayList<Class>();
        for (Class<? extends Annotation> cl : clazz) {
            results.addAll(getAnnotatedClasses(cl));
        }
        return results;
    }

    // ~~~ Intern
    List<ApplicationClass> getAllClasses(String basePackage) {
        List<ApplicationClass> res = new ArrayList<ApplicationClass>();
        for (VirtualFile virtualFile : Play.javaPath) {
            res.addAll(getAllClasses(virtualFile, basePackage));
        }
        return res;
    }

    List<ApplicationClass> getAllClassOfDirectory(VirtualFile path) {
        return getAllClasses(path, "");
    }

    List<ApplicationClass> getAllClasses(VirtualFile path, String basePackage) {
        if (basePackage.length() > 0 && !basePackage.endsWith(".")) {
            basePackage += ".";
        }
        List<ApplicationClass> res = new ArrayList<ApplicationClass>();
        for (VirtualFile virtualFile : path.listChildrenFileOrDirectory()) {
            addClassOfDirectoryAndSubDirectory(res, basePackage, virtualFile);
        }
        return res;
    }

    /**
     * 添加文件夹和子文件夹里面的类
     * @param classes
     * @param packageName
     * @param current
     */
    void addClassOfDirectoryAndSubDirectory(List<ApplicationClass> classes, String packageName, VirtualFile current) {
        if (!current.isDirectory()) {
            if (current.getName().endsWith(".java") && !current.getName().startsWith(".")) {
                String classname = packageName + current.getName().substring(0, current.getName().length() - 5);
                classes.add(Play.classes.createCacheOfClass(classname));//获取应用类创建或者重用
            }
        } else {
            for (VirtualFile virtualFile : current.listChildrenFileOrDirectory()) {
                addClassOfDirectoryAndSubDirectory(classes, packageName + current.getName() + ".", virtualFile);
            }
        }
    }

    void scanPrecompiled(List<ApplicationClass> classes, String packageName, VirtualFile current) {
        if (!current.isDirectory()) {
            if (current.getName().endsWith(".class") && !current.getName().startsWith(".")) {
                String classname = packageName.substring(5) + current.getName().substring(0, current.getName().length() - 6);
                classes.add(new ApplicationClass(classname));
            }
        } else {
            for (VirtualFile virtualFile : current.listChildrenFileOrDirectory()) {
                scanPrecompiled(classes, packageName + current.getName() + ".", virtualFile);
            }
        }
    }

    @Override
    public String toString() {
        return "(play) " + (allClasses == null ? "" : allClasses.toString());
    }

}
