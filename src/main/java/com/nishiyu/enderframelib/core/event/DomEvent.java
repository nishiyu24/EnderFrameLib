package com.nishiyu.enderframelib.core.event;

import com.nishiyu.enderframelib.core.dom.HtmlNode;

/**
 * DOM イベントオブジェクト（W3C DOM Event 仕様準拠）。
 *
 * キャプチャ・バブリングフェーズ、stopPropagation()、preventDefault()、
 * ターゲットノードおよび現在処理中ノードの参照をカプセル化する。
 */
public class DomEvent {

    private final String type;
    private final boolean bubbles;
    private final boolean cancelable;

    private HtmlNode target;
    private HtmlNode currentTarget;
    private EventPhase eventPhase = EventPhase.NONE;

    private boolean defaultPrevented = false;
    private boolean stopPropagation = false;
    private boolean stopImmediatePropagation = false;

    // マウス/入力用の追加情報
    private double clientX;
    private double clientY;
    private int button;
    private String detail = "";

    public DomEvent(String type, boolean bubbles, boolean cancelable) {
        this.type = type != null ? type : "";
        this.bubbles = bubbles;
        this.cancelable = cancelable;
    }

    public static DomEvent click(int button) {
        return click(0, 0, button);
    }

    public static DomEvent contextMenu(int button) {
        DomEvent ev = new DomEvent("contextmenu", true, true);
        ev.button = button;
        return ev;
    }

    public static DomEvent click(double clientX, double clientY, int button) {
        DomEvent ev = new DomEvent("click", true, true);
        ev.clientX = clientX;
        ev.clientY = clientY;
        ev.button = button;
        return ev;
    }

    public static DomEvent input(String value) {
        DomEvent ev = new DomEvent("input", true, false);
        ev.detail = value;
        return ev;
    }

    public static DomEvent change(String value) {
        DomEvent ev = new DomEvent("change", true, false);
        ev.detail = value;
        return ev;
    }

    public static DomEvent submit() {
        return new DomEvent("submit", true, true);
    }

    public static DomEvent reset() {
        return new DomEvent("reset", true, true);
    }

    public String type() {
        return type;
    }

    public boolean bubbles() {
        return bubbles;
    }

    public boolean cancelable() {
        return cancelable;
    }

    public HtmlNode target() {
        return target;
    }

    public void setTarget(HtmlNode target) {
        this.target = target;
    }

    public HtmlNode currentTarget() {
        return currentTarget;
    }

    public void setCurrentTarget(HtmlNode currentTarget) {
        this.currentTarget = currentTarget;
    }

    public EventPhase eventPhase() {
        return eventPhase;
    }

    public void setEventPhase(EventPhase eventPhase) {
        this.eventPhase = eventPhase;
    }

    public boolean isDefaultPrevented() {
        return defaultPrevented;
    }

    public void preventDefault() {
        if (cancelable) {
            this.defaultPrevented = true;
        }
    }

    public boolean isPropagationStopped() {
        return stopPropagation;
    }

    public void stopPropagation() {
        this.stopPropagation = true;
    }

    public boolean isImmediatePropagationStopped() {
        return stopImmediatePropagation;
    }

    public void stopImmediatePropagation() {
        this.stopPropagation = true;
        this.stopImmediatePropagation = true;
    }

    public double clientX() {
        return clientX;
    }

    public double clientY() {
        return clientY;
    }

    public int button() {
        return button;
    }

    public String detail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    /**
     * DOM イベントの伝播フェーズ（W3C DOM Level 2 Event 仕様準拠）。
     */
    public enum EventPhase {
        NONE,
        CAPTURING_PHASE,
        AT_TARGET,
        BUBBLING_PHASE
    }
}
