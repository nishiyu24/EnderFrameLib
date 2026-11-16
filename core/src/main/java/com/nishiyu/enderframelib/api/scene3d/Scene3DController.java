package com.nishiyu.enderframelib.api.scene3d;

import java.util.Optional;
import org.jetbrains.annotations.ApiStatus;

/**
 * 文書の {@code <scene-3d>} 1 つを Java から操作する取っ手（{@code WebViewSurface#scene3d(String)} で得る）。
 * 文書に書いた子要素（{@code <mesh-3d>} など）と、ここから足したノードは同じ世界に同居する（文書の更新はここで足したノードに触れない）。
 * 角度は度、色は ARGB（非事前乗算）、座標はワールド単位。スレッドセーフではない（面と同じスレッドから使う）。
 */
@ApiStatus.AvailableSince("1.0")
public interface Scene3DController {

    /** 対応する要素の {@code id}。 */
    String elementId();

    // ---------------------------------------------------------------- 入力

    /**
     * 要素内の座標 {@code (localX, localY)}（要素の左上が原点の CSS px）から 3D の最も近い面を選ぶ。要素の大きさが未確定（まだ一度も
     * 配置されていない）か、何にも当たらなければ空。
     */
    Optional<Hit3DResult> raycast(double localX, double localY);

    /**
     * 面の座標 {@code (x, y)}（文書の左上が原点の CSS px）から選ぶ。2D の要素が上に重なっていればそちらが先に当たり、3D は選ばれない
     * （マウスが 3D の上にあるかの判定にも使える）。
     */
    Optional<Hit3DResult> pick(double x, double y);

    // ---------------------------------------------------------------- シーンの組み立て

    /** 箱を足す。戻り値はノード番号。 */
    int addBox(double width, double height, double depth, int colorArgb);

    /** 球を足す。戻り値はノード番号。 */
    int addSphere(double radius, int colorArgb);

    /**
     * glTF（{@code .glb}、または {@code data:} URI で埋め込んだ {@code .gltf}）を読んで足す。壊れた・未対応の入力は
     * {@link IllegalArgumentException}（シーンは変わらない）。戻り値は足したモデルの根のノード番号。
     */
    int[] loadGltf(byte[] data);

    void setPosition(int nodeId, double x, double y, double z);

    void setRotationDegrees(int nodeId, double rx, double ry, double rz);

    void setScale(int nodeId, double sx, double sy, double sz);

    void setVisible(int nodeId, boolean visible);

    /** ノードと、その子孫を外す。 */
    void removeNode(int nodeId);

    // ---------------------------------------------------------------- カメラ・ライト・見た目

    /** カメラを注視点のまわりで回す（度。正の {@code yaw} でカメラは右（+X 側）へ、正の {@code pitch} で上へ動く。仰角は ±89° に抑える）。 */
    void orbit(double yawDegrees, double pitchDegrees);

    /** 注視点との距離を {@code factor} 倍にする（1 未満で近づく）。 */
    void zoom(double factor);

    void setCamera(double eyeX, double eyeY, double eyeZ, double targetX, double targetY, double targetZ, double fovDegrees);

    /** 主ライト（指向性）。{@code (dx, dy, dz)} は光が進む向き。 */
    void setDirectionalLight(double dx, double dy, double dz, int colorArgb, double intensity);

    void setAmbientLight(int colorArgb, double intensity);

    /** ポイントライトを足す（最大 8 灯の追加ライトのうちの 1 つ）。満杯なら {@code false}。 */
    boolean addPointLight(double x, double y, double z, int colorArgb, double intensity, double range);

    /** スポットライトを足す。満杯なら {@code false}。 */
    boolean addSpotLight(double x, double y, double z, double dx, double dy, double dz, int colorArgb, double intensity,
                         double range, double innerDegrees, double outerDegrees);

    void clearExtraLights();

    void setBackground(int colorArgb);

    /** ブルーム（HDR の光のにじみ）。{@code sigmaPixels} はにじみの広がり。 */
    void setBloom(boolean enabled, double threshold, double intensity, double sigmaPixels);
}
