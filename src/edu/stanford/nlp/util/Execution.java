package edu.stanford.nlp.util;

import edu.stanford.nlp.util.logging.StanfordRedwoodConfiguration;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.annotation.*;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static edu.stanford.nlp.util.logging.Redwood.Util.*;

/*
 *  TODO(gabor) options for non-static files
*/

/**
 * A class to set command line options. To use, create a static class into which you'd like
 * to put your properties. Then, for each field, set the annotation:
 *
 * <pre>
 *   <code>
 *     import edu.stanford.nlp.util.Execution.Option
 *
 *     class Props {
 *       &#64;Option(name="anIntOption", required=false)
 *       public static int anIntOption = 7 // default value is 7
 *       &#64;Option(name="anotherOption", required=false)
 *       public static File aCastableOption = new File("/foo")
 *     }
 *   </code>
 * </pre>
 *
 * <p>
 *   You can then set options with {@link Execution#exec(Runnable, String[])},
 *   or with {@link Execution#fillOptions(java.util.Properties)}.
 * </p>
 *
 * <p>
 *   If your default classpath has many classes in it, you can select a subset of them by
 *   setting {@link Execution#optionClasses}.
 * </p>
 */
public class Execution {

  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.FIELD)
  public @interface Option {
    String name() default "";

    String gloss() default "";

    boolean required() default false;

    String alt() default "";
  }

  private static final String[] IGNORED_JARS = {
  };
  private static final Class[] BOOTSTRAP_CLASSES = {
      Execution.class,
  };

  @Option(name = "option_classes", gloss = "Fill options from these classes")
  public static Class<?>[] optionClasses = null;
  @Option(name = "threads", gloss = "Number of threads on machine")
  public static int threads = Runtime.getRuntime().availableProcessors();
  @Option(name = "host", gloss = "Name of computer we are running on")
  public static String host = "(unknown)";

  static {
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (Exception ignored) {
    }
  }

  /**
   * A lazy iterator over files, not loading all of them into memory at once.
   */
  public static class LazyFileIterator implements Iterator<File> {

    private FilenameFilter filter;
    private File[] dir;
    private Stack<File[]> parents = new Stack<File[]>();
    private Stack<Integer> indices = new Stack<Integer>();

    private int toReturn = -1;


    public LazyFileIterator(String path) {
      this(new File(path));
    }

    public LazyFileIterator(File path) {
      this(path, new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
          return true;
        }
      });
    }

    public LazyFileIterator(String path, FilenameFilter filter) {
      this(new File(path), filter);
    }

    public LazyFileIterator(String path, final String filter) {
      this(new File(path), filter);
    }

    public LazyFileIterator(File path, final String filter) {
      this(path, new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
          String path = (dir.getPath() + "/" + name);
          return new File(path).isDirectory() || path.matches(filter);
        }
      });
    }

    public LazyFileIterator(File dir, FilenameFilter filter) {
      if (!dir.exists()) throw new IllegalArgumentException("Could not find directory: " + dir.getPath());
      if (!dir.isDirectory()) throw new IllegalArgumentException("Not a directory: " + dir.getPath());
      this.filter = filter;
      this.dir = dir.listFiles(filter);
      enqueue();
    }

    private void enqueue() {
      toReturn += 1;
      boolean good = (toReturn < dir.length && !dir[toReturn].isDirectory());
      while (!good) {
        if (toReturn >= dir.length) {
          //(case: pop)
          if (parents.isEmpty()) {
            toReturn = -1;
            return;  //this is where we exit
          } else {
            dir = parents.pop();
            toReturn = indices.pop();
          }
        } else if (dir[toReturn].isDirectory()) {
          //(case: push)
          parents.push(dir);
          indices.push(toReturn + 1);
          dir = dir[toReturn].listFiles(filter);
          toReturn = 0;
        } else {
          throw new IllegalStateException("File is invalid, but in range and not a directory: " + dir[toReturn]);
        }
        //(check if good)
        good = (toReturn < dir.length && !dir[toReturn].isDirectory());
      }
      // if we reach here we found something
    }

    @Override
    public boolean hasNext() {
      return toReturn >= 0;
    }

    @Override
    public File next() {
      if (toReturn >= dir.length || toReturn < 0) throw new IllegalStateException("No more elements!");
      File rtn = dir[toReturn];
      enqueue();
      return rtn;
    }

    @Override
    public void remove() {
      throw new IllegalArgumentException("NOT IMPLEMENTED");
    }

  }



	/*
   * ----------
	 * OPTIONS
	 * ----------
	 */

  private static void fillField(Field f, String value) {
    try {
      //--Permissions
      boolean accessState = true;
      if (Modifier.isFinal(f.getModifiers())) {
        fatal("Option cannot be final: " + f);
      }
      if (!f.isAccessible()) {
        accessState = false;
        f.setAccessible(true);
      }
      //--Set Value
      Object objVal = MetaClass.cast(value, f.getGenericType());
      if (objVal != null) {
        if (objVal.getClass().isArray()) {
          //(case: array)
          Object[] array = (Object[]) objVal;
          // error check
          if (!f.getType().isArray()) {
            fatal("Setting an array to a non-array field. field: " + f + " value: " + Arrays.toString(array) + " src: " + value);
          }
          // create specific array
          Object toSet = Array.newInstance(f.getType().getComponentType(), array.length);
          for (int i = 0; i < array.length; i++) {
            Array.set(toSet, i, array[i]);
          }
          // set value
          f.set(null, toSet);
        } else {
          //case: not array
          f.set(null, objVal);
        }
      } else {
        fatal("Cannot assign option field: " + f + " value: " + value + "; invalid type");
      }
      //--Permissions
      if (!accessState) {
        f.setAccessible(false);
      }
    } catch (IllegalArgumentException e) {
      err(e);
      fatal("Cannot assign option field: " + f.getDeclaringClass().getCanonicalName() + "." + f.getName() + " value: " + value + " cause: " + e.getMessage());
    } catch (IllegalAccessException e) {
      err(e);
      fatal("Cannot access option field: " + f.getDeclaringClass().getCanonicalName() + "." + f.getName());
    } catch (Exception e) {
      err(e);
      fatal("Cannot assign option field: " + f.getDeclaringClass().getCanonicalName() + "." + f.getName() + " value: " + value + " cause: " + e.getMessage());
    }
  }

  @SuppressWarnings("rawtypes")
  private static final Class filePathToClass(String cpEntry, String path) {
    if (path.length() <= cpEntry.length()) {
      throw new IllegalArgumentException("Illegal path: cp=" + cpEntry
          + " path=" + path);
    }
    if (path.charAt(cpEntry.length()) != '/') {
      throw new IllegalArgumentException("Illegal path: cp=" + cpEntry
          + " path=" + path);
    }
    path = path.substring(cpEntry.length() + 1);
    path = path.replaceAll("/", ".").substring(0, path.length() - 6);
    try {
      return Class.forName(path,
          false,
          ClassLoader.getSystemClassLoader());
    } catch (ClassNotFoundException e) {
      throw fail("Could not load class at path: " + path);
    } catch (NoClassDefFoundError ex) {
      warn("Class at path " + path + " is unloadable");
      return null;
    }
  }

  private static final boolean isIgnored(String path) {
    for (String ignore : IGNORED_JARS) {
      if (path.endsWith(ignore)) {
        return true;
      }
    }
    return false;
  }

  public static final Class<?>[] getVisibleClasses() {
    //--Variables
    List<Class<?>> classes = new ArrayList<Class<?>>();
    // (get classpath)
    String pathSep = System.getProperty("path.separator");
    String[] cp = System.getProperties().getProperty("java.class.path",
        null).split(pathSep);
    // --Fill Options
    // (get classes)
    for (String entry : cp) {
      log("Checking cp " + entry);
      //(should skip?)
      if (entry.equals(".") || entry.trim().length() == 0) {
        continue;
      }
      //(no, don't skip)
      File f = new File(entry);
      if (f.isDirectory()) {
        // --Case: Files
        LazyFileIterator iter = new LazyFileIterator(f, ".*class$");
        while (iter.hasNext()) {
          //(get the associated class)
          Class<?> clazz = filePathToClass(entry, iter.next().getPath());
          if (clazz != null) {
            //(add the class if it's valid)
            classes.add(clazz);
          }
        }
      } else if (!isIgnored(entry)) {
        // --Case: Jar
        try {
          JarFile jar = new JarFile(f);
          Enumeration<JarEntry> e = jar.entries();
          while (e.hasMoreElements()) {
            //(for each jar file element)
            JarEntry jarEntry = e.nextElement();
            String clazz = jarEntry.getName();
            if (clazz.matches(".*class$")) {
              //(if it's a class)
              clazz = clazz.substring(0, clazz.length() - 6)
                  .replaceAll("/", ".");
              //(add it)
              try {
                classes.add(
                    Class.forName(clazz,
                        false,
                        ClassLoader.getSystemClassLoader()));
              } catch (ClassNotFoundException ex) {
                warn("Could not load class in jar: " + f + " at path: " + clazz);
              } catch (NoClassDefFoundError ex) {
                debug("Could not scan class: " + clazz + " (in jar: " + f + ")");
              }
            }
          }
        } catch (IOException e) {
          warn("Could not open jar file: " + f + "(are you sure the file exists?)");
        }
      } else {
        //case: ignored jar
      }
    }

    return classes.toArray(new Class<?>[classes.size()]);
  }

  protected static final Map<String, Field> fillOptions(
      Class<?>[] classes,
      Properties options) {
    return fillOptions(classes, options, false);
  }

  @SuppressWarnings("rawtypes")
  protected static final Map<String, Field> fillOptions(
      Class<?>[] classes,
      Properties options,
      boolean ensureAllOptions) {

    //--Get Fillable Options
    Map<String, Field> canFill = new HashMap<String, Field>();
    Map<String, Pair<Boolean, Boolean>> required = new HashMap<String, Pair<Boolean, Boolean>>(); /* <exists, is_set> */
    Map<String, String> interner = new HashMap<String, String>();
    for (Class c : classes) {
      Field[] fields = null;
      try {
        fields = c.getDeclaredFields();
      } catch (Throwable e) {
        debug("Could not check fields for class: " + c.getName() + "  (caused by " + e.getClass() + ": " + e.getMessage() + ")");
        continue;
      }

      for (Field f : fields) {
        Option o = f.getAnnotation(Option.class);
        if (o != null) {
          //(check if field is static)
          if ((f.getModifiers() & Modifier.STATIC) == 0) {
            fatal("Option can only be applied to static field: " + c + "." + f);
          }
          //(required marker)
          Pair<Boolean, Boolean> mark = Pair.makePair(false, false);
          if (o.required()) {
            mark = Pair.makePair(true, false);
          }
          //(add main name)
          String name = o.name().toLowerCase();
          if (name.equals("")) {
            name = f.getName().toLowerCase();
          }
          if (canFill.containsKey(name)) {
            String name1 = canFill.get(name).getDeclaringClass().getCanonicalName() + "." + canFill.get(name).getName();
            String name2 = f.getDeclaringClass().getCanonicalName() + "." + f.getName();
            if (!name1.equals(name2)) {
              fatal("Multiple declarations of option " + name + ": " + name1 + " and " + name2);
            } else {
              err("Class is in classpath multiple times: " + canFill.get(name).getDeclaringClass().getCanonicalName());
            }
          }
          canFill.put(name, f);
          required.put(name, mark);
          interner.put(name, name);
          //(add alternate names)
          if (!o.alt().equals("")) {
            for (String alt : o.alt().split(" *, *")) {
              alt = alt.toLowerCase();
              if (canFill.containsKey(alt) && !alt.equals(name))
                throw new IllegalArgumentException("Multiple declarations of option " + alt + ": " + canFill.get(alt) + " and " + f);
              canFill.put(alt, f);
              if (mark.first) required.put(alt, mark);
              interner.put(alt, name);
            }
          }
        }
      }
    }

    //--Fill Options
    for (Object rawKey : options.keySet()) {
      String rawKeyStr = rawKey.toString();
      String key = rawKey.toString().toLowerCase();
      // (get values)
      String value = options.get(rawKey).toString();
      assert value != null;
      Field target = canFill.get(key);
      // (mark required option as fulfilled)
      Pair<Boolean, Boolean> mark = required.get(key);
      if (mark != null && mark.first) {
        required.put(key.toString(), Pair.makePair(true, true));
      }
      // (fill the field)
      if (target != null) {
        // (case: declared option)
        fillField(target, value);
      } else if (ensureAllOptions) {
        // (case: undeclared option)
        // split the key
        int lastDotIndex = rawKeyStr.lastIndexOf('.');
        if (lastDotIndex < 0) {
          fatal("Unrecognized option: " + key);
        }
        String className = rawKeyStr.substring(0, lastDotIndex);
        String fieldName = rawKeyStr.substring(lastDotIndex + 1);
        // get the class
        Class clazz = null;
        try {
          clazz = ClassLoader.getSystemClassLoader().loadClass(className);
        } catch (Exception e) {
          debug("Could not set option: " + rawKey + "; no such class: " + className);
        }
        // get the field
        if (clazz != null) {
          try {
            target = clazz.getField(fieldName);
          } catch (Exception e) {
            debug("Could not set option: " + rawKey + "; no such field: " + fieldName + " in class: " + className);
          }
        fillField(target, value);
        }
      }
    }

    //--Ensure Required
    boolean good = true;
    for (String key : required.keySet()) {
      Pair<Boolean, Boolean> mark = required.get(key);
      if (mark.first && !mark.second) {
        err("Missing required option: " + interner.get(key) + "   <in class: " + canFill.get(key).getDeclaringClass() + ">");
        required.put(key, Pair.makePair(true, true));  //don't duplicate error messages
        good = false;
      }
    }
    if (!good) {
      System.exit(1);
    }

    return canFill;
  }

	/*
	 * ----------
	 * EXECUTION
	 * ----------
	 */

  public static void fillOptions(Properties props, String[] args) {
    //(convert to map)
    Properties options = StringUtils.argsToProperties(args);
    for (String key : props.stringPropertyNames()) {
      options.put(key, props.getProperty(key));
    }
    //(bootstrap)
    fillOptions(BOOTSTRAP_CLASSES, options, false); //bootstrap
    //(fill options)
    Class<?>[] visibleClasses = optionClasses;
    if (visibleClasses == null) visibleClasses = getVisibleClasses(); //get classes
    Map<String, Field> optionFields = fillOptions(visibleClasses, options);//fill
  }

  public static void fillOptions(Properties props) {
    fillOptions(props, new String[0]);
  }

  public static final void usingOptions(Class<?>[] classes,
                                        String[] args) {
    Properties options = StringUtils.argsToProperties(args); //get options
    fillOptions(BOOTSTRAP_CLASSES, options, false); //bootstrap
    fillOptions(classes, options);
  }

  public static final void usingOptions(Class<?> clazz,
                                        String[] args) {
    Class<?>[] classes = new Class<?>[1];
    classes[0] = clazz;
    usingOptions(classes, args);
  }

  public static final void exec(Runnable toRun) {
    exec(toRun, new String[0]);
  }

  public static void exec(Runnable toRun, String[] args) {
    exec(toRun, args, false);
  }

  public static void exec(Runnable toRun, String[] args, boolean exit) {
    exec(toRun, StringUtils.argsToProperties(args), exit);
  }

  public static void exec(Runnable toRun, Properties options) {
    exec(toRun, options, false);
  }

  public static void exec(Runnable toRun, Properties options, boolean exit) {
    //--Init
    //(bootstrap)
    fillOptions(BOOTSTRAP_CLASSES, options, false); //bootstrap
    startTrack("init");
    //(fill options)
    Class<?>[] visibleClasses = optionClasses;
    if (visibleClasses == null) visibleClasses = getVisibleClasses(); //get classes
    Map<String, Field> optionFields = fillOptions(visibleClasses, options);//fill
    endTrack("init");
    // -- Setup Logging
    StanfordRedwoodConfiguration.apply(options);
    //--Run Program
    int exitCode = 0;
    startTrack("main");
    try {
      toRun.run();
    } catch (Throwable t) {
      log(FORCE, t);
      exitCode = 1;
    }
    endTrack("main"); //ends main
    if (exit) {
      System.exit(exitCode);
    }
  }

  private static final String threadRootClass() {
    StackTraceElement[] trace = Thread.currentThread().getStackTrace();
    StackTraceElement elem = trace[trace.length - 1];
    String clazz = elem.getClassName();
    return clazz;
  }

  public static final void usageAndExit(String[] expectedArgs) {
    String clazz = threadRootClass();
    StringBuilder b = new StringBuilder();
    b.append("USAGE: ").append(clazz).append(" ");
    for (String arg : expectedArgs) {
      b.append(arg).append(" ");
    }
    System.out.println(b.toString());
    System.exit(0);
  }

  public static final void usageAndExit(Map<String, String[]> argToFlagsMap) {
    String clazz = threadRootClass();
    StringBuilder b = new StringBuilder();
    b.append("USAGE: ").append(clazz).append("\n\t");
    for (String arg : argToFlagsMap.keySet()) {
      String[] flags = argToFlagsMap.get(arg);
      if (flags == null || flags.length == 0) {
        throw new IllegalArgumentException(
            "No flags registered for arg: " + arg);
      }
      b.append("{");
      for (int i = 0; i < flags.length - 1; i++) {
        b.append(flags[i]).append(",");
      }
      b.append(flags[flags.length - 1]).append("}");
    }
    System.out.println(b.toString());
    System.exit(0);
  }


}