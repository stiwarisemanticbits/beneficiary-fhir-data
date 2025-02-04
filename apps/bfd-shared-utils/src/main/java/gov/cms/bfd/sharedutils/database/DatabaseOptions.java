package gov.cms.bfd.sharedutils.database;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/** The user-configurable options that specify how to access the application's database. */
@EqualsAndHashCode
public final class DatabaseOptions {
  /** Regex that matches proper JDBC URLs and extracts groups containing their host and port. */
  private static final Pattern URL_PATTERN =
      Pattern.compile("^jdbc:[a-z]+://([^:/]+)(:([0-9]+))?/", Pattern.CASE_INSENSITIVE);

  /** Group number for host matched by {@link #URL_PATTERN}. */
  private static final int URL_HOST_GROUP = 1;

  /** Group number for port matched by {@link #URL_PATTERN}. */
  private static final int URL_PORT_GROUP = 3;

  /** Used to define how to authenticate with the database. */
  public enum AuthenticationType {
    /** Authenticate using plain JDBC authentication with a provided password. * */
    JDBC,
    /** Authentication using an RDS generated token in place of a provided password. * */
    RDS
  }

  /** How to authenticate with the database. */
  @Getter private final AuthenticationType authenticationType;

  /** The JDBC URL of the database. */
  @Getter private final String databaseUrl;

  /** The username for the database. */
  @Getter private final String databaseUsername;

  /** The password for the database. */
  @Getter private final String databasePassword;

  /** The maximum size of the database connection pool. */
  @Getter private final int maxPoolSize;

  /**
   * Initializes an instance. The builder class generated by lombok calls this constructor.
   *
   * @param authenticationType optional value to use for {@link #authenticationType}
   * @param databaseUrl the value to use for {@link #databaseUrl}
   * @param databaseUsername the value to use for {@link #databaseUsername}
   * @param databasePassword the value to use for {@link #databasePassword}
   * @param maxPoolSize the value to use for {@link #maxPoolSize}
   */
  @Builder(toBuilder = true)
  private DatabaseOptions(
      AuthenticationType authenticationType,
      String databaseUrl,
      String databaseUsername,
      String databasePassword,
      int maxPoolSize) {
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(databaseUrl), "databaseUrl must be non-empty");
    Preconditions.checkNotNull(databaseUsername, "databaseUsername must not be null");
    Preconditions.checkArgument(
        authenticationType == AuthenticationType.RDS || databasePassword != null);
    this.authenticationType =
        authenticationType != null ? authenticationType : AuthenticationType.JDBC;
    this.databaseUrl = databaseUrl;
    this.databaseUsername = databaseUsername;
    this.databasePassword = databasePassword;
    this.maxPoolSize = maxPoolSize;
  }

  /**
   * Parses the database host from the {@link #databaseUrl}. Used with RDS authentication.
   *
   * @return host or empty if parsing fails
   */
  public Optional<String> getDatabaseHost() {
    return parseUrl(URL_HOST_GROUP);
  }

  /**
   * Parses the database port from the {@link #databaseUrl}. Used with RDS authentication.
   *
   * @return port or empty if parsing fails
   */
  public Optional<Integer> getDatabasePort() {
    return parseUrl(URL_PORT_GROUP).map(Integer::parseInt);
  }

  /**
   * Parses {@link #databaseUrl} to obtain a match group.
   *
   * @param groupNumber number of the match group to return
   * @return matched group value from the URL (if any)
   */
  private Optional<String> parseUrl(int groupNumber) {
    final Matcher matcher = URL_PATTERN.matcher(databaseUrl);
    if (matcher.find() && matcher.groupCount() >= groupNumber) {
      return Optional.of(matcher.group(groupNumber));
    } else {
      return Optional.empty();
    }
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("DatabaseOptions [databaseUrl=");
    builder.append(databaseUrl);
    builder.append(", databaseUsername=");
    builder.append("***");
    builder.append(", databasePassword=");
    builder.append("***");
    builder.append(", maxPoolSize=");
    builder.append(maxPoolSize);
    builder.append("]");
    return builder.toString();
  }
}
