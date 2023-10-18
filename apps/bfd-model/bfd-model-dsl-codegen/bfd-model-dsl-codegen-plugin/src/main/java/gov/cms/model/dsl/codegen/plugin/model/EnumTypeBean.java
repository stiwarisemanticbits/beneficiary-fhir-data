package gov.cms.model.dsl.codegen.plugin.model;

import com.google.common.base.Strings;
import gov.cms.model.dsl.codegen.plugin.model.validation.JavaName;
import gov.cms.model.dsl.codegen.plugin.model.validation.JavaNameType;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Singular;

/** Model object for custom enum types to be generated by a mapping. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnumTypeBean implements ModelBean {
  /** Unique name of the enum class. */
  @NotNull @JavaName private String name;

  /** Optional package name for this enum if it is not an inner class of the entity. */
  @JavaName(type = JavaNameType.Compound)
  private String packageName;

  /** List of names for the values of the enum. */
  @NotNull @Singular private List<@JavaName String> values = new ArrayList<>();

  /**
   * Looks up a value with the given name and returns the value if it is present. Otherwise throws
   * an {@link IllegalArgumentException} with an error message indicating the value is not valid.
   *
   * @param value enum value to verify
   * @return the value if it is valid for this enum
   * @throws IllegalArgumentException if the value is not valid for this enum
   */
  public String findValue(String value) {
    if (!values.contains(value)) {
      throw new IllegalArgumentException(
          String.format("reference to unknown enum value %s in enum %s", value, name));
    } else {
      return value;
    }
  }

  /**
   * Determines if this enum should be generated as an inner class within the parent entity.
   *
   * @return true if this is an inner class enum
   */
  public boolean isInnerClass() {
    return Strings.isNullOrEmpty(packageName);
  }

  @Override
  public String getDescription() {
    return "enum " + name;
  }
}
