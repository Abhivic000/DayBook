package dev.daybook.api.common.domain;

/**
 * An amount of money in minor units (paise). Signed: system account balances may be negative.
 *
 * <p>Arithmetic is overflow-checked: an overflow throws {@link ArithmeticException} rather than
 * silently wrapping around, which would corrupt a balance. Per-transaction amount limits keep this
 * far out of reach in practice; the check exists so it can never happen silently.
 */
public record Money(long minor) implements Comparable<Money> {

  public static final Money ZERO = new Money(0);

  public static Money ofMinor(long minor) {
    return new Money(minor);
  }

  public Money plus(Money other) {
    return new Money(Math.addExact(minor, other.minor));
  }

  public Money minus(Money other) {
    return new Money(Math.subtractExact(minor, other.minor));
  }

  public boolean isPositive() {
    return minor > 0;
  }

  public boolean isNegative() {
    return minor < 0;
  }

  @Override
  public int compareTo(Money other) {
    return Long.compare(minor, other.minor);
  }
}
