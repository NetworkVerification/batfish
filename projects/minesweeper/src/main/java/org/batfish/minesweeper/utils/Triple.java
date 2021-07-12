package org.batfish.minesweeper.utils;

import java.util.Objects;
import javax.annotation.Nullable;

public class Triple<T1, T2, T3> {

  private T1 _first;

  private T2 _second;

  private T3 _third;

  public Triple(@Nullable T1 first, @Nullable T2 second, @Nullable T3 third) {
    this._first = first;
    this._second = second;
    this._third = third;
  }

  public T1 getFirst() {
    return _first;
  }

  public T2 getSecond() {
    return _second;
  }

  public T3 getThird() {
    return _third;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof Triple<?, ?, ?>)) {
      return false;
    }
    Triple<?, ?, ?> other = (Triple<?, ?, ?>) o;
    return Objects.equals(_first, other._first) && Objects.equals(_second, other._second)
        && Objects.equals(_third, other._third)  ;
  }

  @Override
  public int hashCode() {
    return Objects.hash(_first, _second, _third);
  }

  @Override
  public String toString() {
    return "<" + _first + "," + _second + "," + _third + ">";
  }
}
