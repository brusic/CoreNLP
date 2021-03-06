package edu.stanford.nlp.pipeline;

import edu.stanford.nlp.util.Factory;

import java.util.Properties;

/**
 * A Factory for creating a certain type of Annotator.
 *
 * @author Mihai Surdeanu
 */
public abstract class AnnotatorFactory implements Factory<Annotator> {

  private static final long serialVersionUID = -1554647325549869340L;

  protected final Properties properties;

  protected AnnotatorFactory(Properties properties) {
    // Let's copy the properties, just in case somebody messes with this object later.
    // By using stringPropertyNames(), we also pick up any defaults the Properties has.
    this.properties = new Properties();
    for (String key : properties.stringPropertyNames()) {
      this.properties.setProperty(key, properties.getProperty(key));
    }
  }

  /**
   * Creates and returns an Annotator given the local properties.
   *
   * @return A new instance of the type T
   */
  @Override
  public abstract Annotator create();

  /**
   * Creates the annotator's signature given the current properties.
   * We use this to understand if the user wants to recreate
   * the same annotator type but with different parameters.
   */
  public abstract String signature();
}
