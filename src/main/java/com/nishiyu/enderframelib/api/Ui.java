package com.nishiyu.enderframelib.api;

import com.nishiyu.enderframelib.api.state.UIState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Describes one UI to show. Pass it to {@link EnderFrameAPI#open} (a screen) or
 * {@link EnderFrameAPI#hud} (an overlay); both take the same description, so there is
 * one way to say <em>what</em> to show and one way to say <em>to whom</em>.
 *
 * <pre>{@code
 * api.open(player, Ui.template("mymod:crusher").at(blockEntity));          // machine UI
 * api.open(player, Ui.template("mymod:menu").instance("main").state(s));   // shared state
 * api.open(player, Ui.datapack("mymod:help"));                             // data/<ns>/enderframe_ui/help.html
 * api.open(player, Ui.html(html).size(640, 360));                          // ad hoc
 * api.hud(player, Ui.html(hudHtml));
 * }</pre>
 *
 * <p>{@link Template} is the recommended form: the server only keeps a {@link UIState}
 * and the client renders the template. {@link Html} and {@link Datapack} keep a document
 * on the server and broadcast its DOM, which suits one-off screens and in-world screens.</p>
 */
@ApiStatus.AvailableSince("1.2")
public sealed interface Ui permits Ui.Template, Ui.Datapack, Ui.Html {

    /** A client-side template ({@code assets/<ns>/enderframe_ui/<path>.html}) driven by a {@link UIState}. */
    static Template template(@NotNull String templateId) {
        return new Template(templateId, null, null, null);
    }

    /** A UI declared in a datapack ({@code data/<ns>/enderframe_ui/<path>.html}). */
    static Datapack datapack(@NotNull String uiId) {
        return new Datapack(uiId, 0, 0, null);
    }

    /** A UI from an HTML string. */
    static Html html(@NotNull String html) {
        return new Html(html, 0, 0, null);
    }

    /**
     * A template and the state that feeds it.
     *
     * @param templateId  the client-side template, e.g. {@code "mymod:crusher"}
     * @param instanceId  the id of this instance; ignored when {@code blockEntity} is set
     * @param state       the state, or {@code null} to look it up (or create it) for the instance
     * @param blockEntity the block entity that owns the UI, or {@code null}
     */
    record Template(@NotNull String templateId, @Nullable String instanceId, @Nullable UIState state,
                    @Nullable BlockEntity blockEntity) implements Ui {

        /**
         * The UI of a block entity. The instance id is {@code "block:x,y,z"} from its position, and unless
         * {@link #state} is given the state is the block entity's own (see {@link EnderFrameAPI#stateOf}).
         */
        public Template at(@NotNull BlockEntity blockEntity) {
            return new Template(templateId, instanceId, state, blockEntity);
        }

        public Template instance(@NotNull String instanceId) {
            return new Template(templateId, instanceId, state, blockEntity);
        }

        public Template state(@NotNull UIState state) {
            return new Template(templateId, instanceId, state, blockEntity);
        }

        /** The instance id the library uses for a block entity. */
        public static String blockInstanceId(BlockPos pos) {
            return "block:" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }
    }

    /**
     * A datapack UI.
     *
     * @param width     layout width in CSS pixels; 0 for the default
     * @param height    layout height in CSS pixels; 0 for the default
     * @param sessionId session id; {@code null} to derive one from the UI id
     */
    record Datapack(@NotNull String uiId, int width, int height, @Nullable String sessionId) implements Ui {

        public Datapack size(int width, int height) {
            return new Datapack(uiId, width, height, sessionId);
        }

        public Datapack session(@Nullable String sessionId) {
            return new Datapack(uiId, width, height, sessionId);
        }
    }

    /**
     * An ad hoc HTML UI.
     *
     * @param width     layout width in CSS pixels; 0 for the default
     * @param height    layout height in CSS pixels; 0 for the default
     * @param sessionId session id; {@code null} for a per-player session owned by that player. Give an id to share one
     *                  session between players
     */
    record Html(@NotNull String html, int width, int height, @Nullable String sessionId) implements Ui {

        public Html size(int width, int height) {
            return new Html(html, width, height, sessionId);
        }

        public Html session(@Nullable String sessionId) {
            return new Html(html, width, height, sessionId);
        }
    }
}
