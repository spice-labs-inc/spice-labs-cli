package io.spicelabs.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The edition this build of {@code spice} is, read once at startup from a feature manifest
 * on the classpath.
 *
 * <p>Whoever assembles {@code spice} decides what it can do: one distribution may survey
 * artifact repositories in bulk but never upload, another may survey and upload but have no
 * bulk command. It declares that by shipping {@value #RESOURCE} beside the classes:
 *
 * <pre>
 * edition=compact
 * edition.name=Example Compact Edition
 * airgapped=true
 * features=bulk,static-detect,cbom-material
 * licence.key=spice-licence.pub
 * </pre>
 *
 * <p>No manifest, or a manifest without a {@code features} key, means {@link #UNRESTRICTED}:
 * a build nobody has narrowed offers everything, which is what an ordinary
 * {@code mvn package} of this repository produces.
 *
 * <p>Only the features naming this CLI's own built-in commands are acted on; any others are
 * carried without comment. A distribution describes its whole edition in that list, but a
 * capability a plugin provides needs nothing from here: the plugin is either on the
 * classpath or it is not, which answers the same question more directly.
 *
 * <p>{@code licence.key}, when present, names a PEM public key on the classpath and makes the
 * edition one that <em>requires a licence</em>: a run must hold an airgapped Spice Pass that
 * verifies against it ({@link Licence}). The key is optional and its absence means
 * no check; a value that is present but unusable — a resource that is not on the classpath, or
 * a {@code ${…}} placeholder a build never filled in — is kept as written so that the check
 * refuses with the real cause rather than treating the build as unlicensed.
 *
 * <p>Everything that gates on the edition asks {@link #current()}: the command tree
 * ({@link EditionGate}), the upload paths of the survey commands, the licence check, and the
 * plugins through {@link DefaultSpiceContext#edition()}.
 */
final class Edition {

  /** The features this CLI acts on. A manifest may name others; those are for plugins. */
  static final String INVENTORY_SURVEYS = "inventory-surveys";
  static final String RUNTIME_SURVEYS = "runtime-surveys";

  /** Where a distribution declares this build's edition; absent in an ordinary build. */
  static final String RESOURCE = "/spice-edition.properties";

  private static final Logger log = LoggerFactory.getLogger(Edition.class);

  static final Edition UNRESTRICTED = new Edition("", "", false, Set.of(), null, true);

  private static volatile Edition current;

  private final String id;
  private final String displayName;
  private final boolean airgapped;
  private final Set<String> features;
  private final String licenceKey;
  private final boolean unrestricted;

  private Edition(String id, String displayName, boolean airgapped, Set<String> features,
                  String licenceKey, boolean unrestricted) {
    this.id = id;
    this.displayName = displayName.isEmpty() ? id : displayName;
    this.airgapped = airgapped;
    this.features = Collections.unmodifiableSet(new LinkedHashSet<>(features));
    this.licenceKey = licenceKey;
    this.unrestricted = unrestricted;
  }

  /** Whether this edition has the named feature; an unnarrowed build has them all. */
  boolean has(String feature) {
    return unrestricted || features.contains(feature);
  }

  /** The edition of this run, read from the classpath on first use. */
  static Edition current() {
    Edition edition = current;
    if (edition == null) {
      edition = load();
      current = edition;
    }
    return edition;
  }

  /** Tests only: make a chosen edition the current one ({@code null} re-reads the manifest). */
  static void install(Edition edition) {
    current = edition;
  }

  /** The manifest on the classpath, or {@link #UNRESTRICTED} when there is none. */
  static Edition load() {
    try (InputStream in = Edition.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        return UNRESTRICTED;
      }
      Properties properties = new Properties();
      properties.load(in);
      return parse(properties);
    } catch (IOException e) {
      log.warn("Could not read {}: {}; treating this build as unrestricted", RESOURCE, e.getMessage());
      return UNRESTRICTED;
    }
  }

  /** The edition a manifest describes; unrestricted when it names no features. */
  static Edition parse(Properties properties) {
    String features = properties.getProperty("features");
    if (features == null) {
      return UNRESTRICTED;
    }
    Set<String> declared = new LinkedHashSet<>();
    for (String feature : features.split(",")) {
      String name = feature.trim();
      if (!name.isEmpty()) {
        declared.add(name);
      }
    }
    String id = properties.getProperty("edition", "").trim();
    String name = properties.getProperty("edition.name", "").trim();
    boolean airgapped = Boolean.parseBoolean(properties.getProperty("airgapped", "false").trim());
    String key = properties.getProperty("licence.key", "").trim();
    return new Edition(id, name, airgapped, declared, key.isEmpty() ? null : key, false);
  }

  /** Tests only: an edition built from its parts. */
  static Edition of(String id, String displayName, boolean airgapped, String... features) {
    return new Edition(id, displayName, airgapped, Set.of(features), null, false);
  }

  /** Tests only: this edition, but requiring a licence against the named classpath key. */
  Edition licensedBy(String keyResource) {
    return new Edition(id, displayName, airgapped, features, keyResource, unrestricted);
  }

  String id() {
    return id;
  }

  String displayName() {
    return displayName;
  }

  boolean airgapped() {
    return airgapped;
  }

  Set<String> features() {
    return features;
  }

  /** Whether a run of this edition must hold a licence (the manifest names a key). */
  boolean requiresLicence() {
    return licenceKey != null;
  }

  /** The classpath resource holding the licensing public key, as the manifest wrote it. */
  String licenceKey() {
    return licenceKey;
  }

  /** Whether this build carries a manifest at all (an unbranded build has no identifier). */
  boolean branded() {
    return !id.isEmpty();
  }

  /** One line for {@code --version}: the product, its identifier and what it can do. */
  String describe() {
    StringBuilder sb = new StringBuilder(displayName).append(" (").append(id).append("): ");
    if (airgapped) {
      sb.append("airgapped; ");
    }
    if (requiresLicence()) {
      sb.append("licensed; ");
    }
    return sb.append("features: ").append(String.join(", ", features)).toString();
  }

  @Override
  public String toString() {
    return branded() ? "Edition[" + describe() + "]" : "Edition[unrestricted]";
  }
}
