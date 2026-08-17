package nl.gzmn.playerworlds.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the Paper item serialisation methods F5 depends on (plan section 5.5).
 *
 * <p>{@link PaperItemCodec} already fails compilation if the methods are renamed.
 * This test fails with a clearer message if signatures drift (extra parameters,
 * loss of static-ness on deserialize) and constructs the codec so the binding
 * is exercised from the test classpath.
 *
 * <p>A behavioural byte round-trip of a real item needs a running Paper server
 * (the methods bottom out in the server's InternalAPIBridge). That is covered
 * by loading the jar on a real node; unit tests here hold the API contract.
 */
class ItemCodecApiPinTest {

    @Test
    @DisplayName("ItemStack.serializeAsBytes and deserializeBytes keep the FR-14 signatures")
    void serializeAsBytesAndDeserializeBytesExist() throws Exception {
        Method serialize = ItemStack.class.getMethod("serializeAsBytes");
        assertThat(Modifier.isStatic(serialize.getModifiers())).isFalse();
        assertThat(serialize.getReturnType()).isEqualTo(byte[].class);

        Method deserialize = ItemStack.class.getMethod("deserializeBytes", byte[].class);
        assertThat(Modifier.isStatic(deserialize.getModifiers())).isTrue();
        assertThat(deserialize.getReturnType()).isEqualTo(ItemStack.class);
    }

    @Test
    @DisplayName("ItemStack array helpers keep the inventory-slot round-trip signatures")
    void serializeItemsAsBytesAndDeserializeItemsFromBytesExist() throws Exception {
        Method serializeArray = ItemStack.class.getMethod("serializeItemsAsBytes", ItemStack[].class);
        assertThat(Modifier.isStatic(serializeArray.getModifiers())).isTrue();
        assertThat(serializeArray.getReturnType()).isEqualTo(byte[].class);

        Method deserializeArray = ItemStack.class.getMethod("deserializeItemsFromBytes", byte[].class);
        assertThat(Modifier.isStatic(deserializeArray.getModifiers())).isTrue();
        assertThat(deserializeArray.getReturnType()).isEqualTo(ItemStack[].class);
    }

    @Test
    @DisplayName("PaperItemCodec is the selected ItemCodec and rejects null inputs")
    void paperItemCodecIsWiredAndNullChecked() {
        ItemCodec codec = PaperItemCodec.INSTANCE;
        assertThat(codec)
                .isSameAs(Platform.create(new ServerIdentity("26.2", Platform.BUILD_DATA_VERSION))
                        .itemCodec());

        assertThatCode(() -> codec.serialize(null)).isInstanceOf(NullPointerException.class);
        assertThatCode(() -> codec.deserialize(null)).isInstanceOf(NullPointerException.class);
        assertThatCode(() -> codec.serializeItems(null)).isInstanceOf(NullPointerException.class);
        assertThatCode(() -> codec.deserializeItems(null)).isInstanceOf(NullPointerException.class);
    }
}
