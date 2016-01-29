package play.classloading;

import javassist.ClassPool;
import javassist.CtClass;
import org.junit.Test;
import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.classloading.enhancers.Enhancer;
import play.exceptions.UnexpectedException;
import play.vfs.VirtualFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Application cacheOfApplicationClass container.
 */
public class ApplicationClasses {

    /**
     * Reference to the eclipse compiler.
     */
    ApplicationCompiler compiler = new ApplicationCompiler(this);
    /**
     * Cache of getAllCopyClasses compileToBytesAndModifyDate cacheOfApplicationClass
     */
    Map<String, ApplicationClass> cacheOfApplicationClass = new HashMap<String, ApplicationClass>();

    /**
     * Clear the cacheOfApplicationClass cache
     */
    public void clear() {
        cacheOfApplicationClass = new HashMap<String, ApplicationClass>();
    }

    /**
     * Get a class by name
     * @param name The fully qualified class name
     * @return The ApplicationClass or null
     */
    public ApplicationClass getApplicationClass(String name) {
        VirtualFile javaFile = getJavaFile(name);
        if(javaFile != null){
            if (!cacheOfApplicationClass.containsKey(name)) {
                cacheOfApplicationClass.put(name, new ApplicationClass(name));
            }
            return cacheOfApplicationClass.get(name);
        }
        return null;
    }

    /**
     * Retrieve getAllCopyClasses application cacheOfApplicationClass assignable to this class.
     * @param clazz The superclass, or the interface.
     * @return A listChildrenFileOrDirectory of application cacheOfApplicationClass.
     */
    public List<ApplicationClass> getAssignableClasses(Class<?> clazz) {
        List<ApplicationClass> results = new ArrayList<ApplicationClass>();
        if (clazz != null) {
            for (ApplicationClass applicationClass : new ArrayList<ApplicationClass>(cacheOfApplicationClass.values())) {
                if (!applicationClass.isClass()) {
                    continue;
                }
                try {
                    Play.classloader.loadClass(applicationClass.name);
                } catch (ClassNotFoundException ex) {
                    throw new UnexpectedException(ex);
                }
                try {
                    if (clazz.isAssignableFrom(applicationClass.javaClass) && !applicationClass.javaClass.getName().equals(clazz.getName())) {
                        results.add(applicationClass);
                    }
                } catch (Exception e) {
                }
            }
        }
        return results;
    }

    /**
     * Retrieve getAllCopyClasses application cacheOfApplicationClass with a specific annotation.
     * @param clazz The annotation class.
     * @return A listChildrenFileOrDirectory of application cacheOfApplicationClass.
     */
    public List<ApplicationClass> getAnnotatedClasses(Class<? extends Annotation> clazz) {
        List<ApplicationClass> results = new ArrayList<ApplicationClass>();
        for (ApplicationClass applicationClass : cacheOfApplicationClass.values()) {
            if (!applicationClass.isClass()) {
                continue;
            }
            try {
                Play.classloader.loadClass(applicationClass.name);
            } catch (ClassNotFoundException ex) {
                throw new UnexpectedException(ex);
            }
            if (applicationClass.javaClass != null && applicationClass.javaClass.isAnnotationPresent(clazz)) {
                results.add(applicationClass);
            }
        }
        return results;
    }

    /**
     * All loaded cacheOfApplicationClass.
     * @return All loaded cacheOfApplicationClass
     */
    public List<ApplicationClass> getAllCopyClasses() {
        return new ArrayList<ApplicationClass>(cacheOfApplicationClass.values());
    }

    /**
     * Put a new class to the cache.
     */
    public void add(ApplicationClass applicationClass) {
        cacheOfApplicationClass.put(applicationClass.name, applicationClass);
    }

    /**
     * Remove a class from cache
     */
    public void remove(ApplicationClass applicationClass) {
        cacheOfApplicationClass.remove(applicationClass.name);
    }

    public void remove(String applicationClass) {
        cacheOfApplicationClass.remove(applicationClass);
    }

    /**
     * Does this class is already loaded ?
     * @param name The fully qualified class name
     */
    public boolean hasClass(String name) {
        return cacheOfApplicationClass.containsKey(name);
    }

    /**
     * Represent a application class
     */
    public static class ApplicationClass {//每个应用类

        /**
         * The fully qualified class name
         */
        public String name;
        /**
         * A reference to the java source file
         */
        public VirtualFile javaFile;
        /**
         * The Java source
         */
        public String javaSourceString;
        /**
         * The compileToBytesAndModifyDate byteCode
         */
        public byte[] javaByteCode;
        /**
         * The enhanced byteCode
         */
        public byte[] enhancedByteCode;
        /**
         * The in JVM loaded class
         */
        public Class<?> javaClass;
        /**
         * The in JVM loaded package
         */
        public Package javaPackage;
        /**
         * Last time than this class was compileToBytesAndModifyDate
         */
        public Long timestamp = 0L;
        /**
         * Is this class compileToBytesAndModifyDate
         */
        boolean compiled;
        /**
         * Signatures checksum
         */
        public int sigChecksum;

        public ApplicationClass() {
        }

        public ApplicationClass(String name) {
            this.name = name;
            this.javaFile = getJavaFile(name);
            this.clearFileByteAndTime();
        }

        /**
         * Need to clearFileByteAndTime this class !
         */
        /**
         * 清空字节和修改时间
         */
        public void clearFileByteAndTime() {
            if (this.javaFile != null) {
                this.javaSourceString = this.javaFile.fileInputToString();
            }
            this.javaByteCode = null;
            this.enhancedByteCode = null;
            this.compiled = false;
            this.timestamp = 0L;
        }

        static final ClassPool enhanceChecker_classPool = Enhancer.newClassPool();
        static final CtClass ctPlayPluginClass = enhanceChecker_classPool.makeClass(PlayPlugin.class.getName());

        /**
         * Enhance this class
         * @return the enhanced byteCode
         */
        public byte[] enhance() {
            this.enhancedByteCode = this.javaByteCode;
            if (isClass()) {

                // before we can start enhancing this class we must make sure it is not a PlayPlugin.
                // PlayPlugins can be included as regular java files in a Play-application.
                // If a PlayPlugin is present in the application, it is loaded when other plugins are loaded.
                // All plugins must be loaded before we can start enhancing.
                // This is a problem when loading PlayPlugins bundled as regular app-class since it uses the same classloader
                // as the other (soon to be) enhanched play-app-cacheOfApplicationClass.
                boolean shouldEnhance = true;
                try {
                    CtClass ctClass = enhanceChecker_classPool.makeClass(new ByteArrayInputStream(this.enhancedByteCode));
                    if (ctClass.subclassOf(ctPlayPluginClass)) {
                        shouldEnhance = false;
                    }
                } catch( Exception e) {
                    // nop
                }

                if (shouldEnhance) {
                    Play.pluginCollection.enhance(this);
                }
            }
            if (System.getProperty("precompile") != null) {
                try {
                    // emit bytecode to standard class layout as well
                    File f = Play.getFile("precompiled/java/" + (name.replace(".", "/")) + ".class");
                    f.getParentFile().mkdirs();
                    FileOutputStream fos = new FileOutputStream(f);
                    try {
                        fos.write(this.enhancedByteCode);
                    }
                    finally {
                        fos.close();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return this.enhancedByteCode;

        }

        /**
         * Is this class already compileToBytesAndModifyDate but not defined ?
         * @return if the class is compileToBytesAndModifyDate but not defined
         */
        public boolean haveCompiled() {
            return compiled && javaClass != null;
        }

        public boolean isClass() {
            return isClass(this.name);
        }

	public static boolean isClass(String name) {
            return !name.endsWith("package-info");
	}

        public String getPackage() {
            int dot = name.lastIndexOf('.');
            return dot > -1 ? name.substring(0, dot) : "";
        }

        /**
         * Compile the class from Java source
         * @return the bytes that comprise the class file
         */
        public byte[] compile() {
            long start = System.currentTimeMillis();
            Play.classes.compiler.compile(new String[]{this.name});

            if (Logger.isTraceEnabled()) {
                Logger.trace("%sms to compile class %s", System.currentTimeMillis() - start, name);
            }

            return this.javaByteCode;
        }

        /**
         * Unload the class
         */
        public void uncompile() {
            this.javaClass = null;
        }

        /**
         * 编译二进制码并修改文件日期
         * Call back when a class is compileToBytesAndModifyDate.
         * @param code The bytecode.
         */
        public void compileToBytesAndModifyDate(byte[] code) {
            javaByteCode = code;
            enhancedByteCode = code;
            compiled = true;
            this.timestamp = this.javaFile.lastModified();
        }

        @Override
        public String toString() {
            return name + " (compileToBytesAndModifyDate:" + compiled + ")";
        }
    }

    // ~~ Utils
    /**
     * 获取Java文件 否则返回空
     * Retrieve the corresponding source file for a given class name.
     * It handles innerClass too !
     * @param name The fully qualified class name 
     * @return The virtualFile if found
     */
    public static VirtualFile getJavaFile(String name) {
        String fileName = name;
        if (fileName.contains("$")) {
            fileName = fileName.substring(0, fileName.indexOf("$"));
        }
        // the local variable fileOrDir is important!
        String fileOrDir = fileName.replace(".", "/");
        fileName = fileOrDir + ".java";
        for (VirtualFile path : Play.javaPath) {
            // 1. check if there is a folder (without extension)
            VirtualFile javaFile = path.children(fileOrDir);
                  
            if (javaFile.exists() && javaFile.isDirectory() && javaFile.matchName(fileOrDir)) {
                // we found a directory (package)
                return null;
            }
            // 2. check if there is a file
            javaFile = path.children(fileName);
            if (javaFile.exists() && javaFile.matchName(fileName)) {
                return javaFile;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return cacheOfApplicationClass.toString();
    }

    @Test
    public static void compileTest(){
    }
}
