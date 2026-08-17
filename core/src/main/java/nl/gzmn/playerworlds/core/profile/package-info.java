/**
 * The per-world profile envelope (FR-14 to FR-17).
 *
 * <p>Platform-independent on purpose: item stacks arrive as opaque
 * version-tagged NBT blobs from {@code backend.platform.ItemCodec}, and this
 * package only decides how our own envelope around them is laid out. The two
 * layers version separately (ADR 0008).
 */
@NullMarked
package nl.gzmn.playerworlds.core.profile;

import org.jspecify.annotations.NullMarked;
