package io.github.retronym.sharing;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Release/acquire access to the slots of an array (an open-addressing hash table read by several threads): an entry
 *  stored with release semantics is seen complete by a reader that loads its slot with acquire semantics. On x86 an
 *  acquire load is a plain load; on Apple silicon it is `ldar`.
 *
 *  A field that publishes others is a `Published` instead, with its handle declared beside its class.
 *
 *  A Java class, so that the handle is a `static final` constant the JIT folds.
 */
public final class Publish {
  private Publish() {}

  private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(Object[].class);

  public static Object slotAcquire(Object[] array, int i) {
    return SLOT.getAcquire(array, i);
  }

  public static void setSlotRelease(Object[] array, int i, Object value) {
    SLOT.setRelease(array, i, value);
  }
}
