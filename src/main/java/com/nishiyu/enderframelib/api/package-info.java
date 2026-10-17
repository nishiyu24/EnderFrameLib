/**
 * The public API of EnderFrameLib.
 *
 * <h2>Stability</h2>
 * Types in this package and its subpackages are the supported surface and carry
 * {@link org.jetbrains.annotations.ApiStatus.AvailableSince}. Everything else in
 * {@code com.nishiyu.enderframelib} is implementation: the classes that are most
 * likely to be reached for by mistake are marked
 * {@link org.jetbrains.annotations.ApiStatus.Internal} and may change or
 * disappear in any release.
 *
 * <h2>Entry points</h2>
 * <ul>
 *   <li>{@link com.nishiyu.enderframelib.api.EnderFrameAPI} — open screens and HUDs,
 *       create sessions, publish pub/sub data, register providers.</li>
 *   <li>{@link com.nishiyu.enderframelib.api.WebSessionHandle} — a live session: load a
 *       document, patch single elements, resize.</li>
 *   <li>{@link com.nishiyu.enderframelib.api.IWebActionReceiver} — receive
 *       {@code onclick="server:…"} actions on a menu or block entity.</li>
 *   <li>{@link com.nishiyu.enderframelib.api.IWebScreenBlockEntity} — expose a
 *       multiblock in-world screen.</li>
 *   <li>{@link com.nishiyu.enderframelib.api.EnderFrameScriptingBridge} — the same
 *       operations as flat statics, for KubeJS and other script runtimes.</li>
 * </ul>
 *
 * <h2>Client side</h2>
 * {@code AbstractWebScreen} and {@code AbstractWebContainerScreen} (in
 * {@code com.nishiyu.enderframelib.client.gui}) are the intended base classes for
 * downstream screens and are treated as API for compatibility purposes even though
 * they live outside this package, because they must extend Minecraft's own
 * {@code Screen} hierarchy.
 *
 * <h2>Comment language</h2>
 * Javadoc in this package is written in English so that it is usable from outside
 * the project; comments inside the implementation packages are Japanese. See
 * {@code CONTRIBUTING.md}.
 */
package com.nishiyu.enderframelib.api;
