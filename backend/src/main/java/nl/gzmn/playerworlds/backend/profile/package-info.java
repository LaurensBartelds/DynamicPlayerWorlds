/**
 * Per-world player profiles (FR-14 to FR-17).
 *
 * <p>Profiles are persisted only as part of a world snapshot commit (FR-15).
 * There is deliberately no autosave timer here: profiles and world data live in
 * different storage systems, and any skew between their durability points is an
 * item duplication bug in one direction and an item destruction bug in the other
 * (FR-15a).
 */
@NullMarked
package nl.gzmn.playerworlds.backend.profile;

import org.jspecify.annotations.NullMarked;
