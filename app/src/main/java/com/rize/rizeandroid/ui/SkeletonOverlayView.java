package com.rize.rizeandroid.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;

import java.util.List;

/**Overlay transparente que dibuja el esqueleto de MediaPipe sobre un VideoView.
 *
 * Los puntos de referencia se almacenan como NormalizedLandmark (x,y en [0,1] relativo al marco del video).
 * La vista replica la escala fitCenter utilizada por VideoView para que el esqueleto se alinee
 * precisamente con el contenido del video incluso cuando el video tiene una relación de aspecto
 * diferente a la del contenedor.
 */
public class SkeletonOverlayView extends View {

    private List<NormalizedLandmark> landmarks;
    private int videoWidth  = 1920;
    private int videoHeight = 1080;

    // Content bounds within this view (letterbox / pillarbox safe area)
    private float drawLeft, drawTop, drawRight, drawBottom;

    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * Constructor para crear la vista desde código. Llama a init() para configurar las pinturas y otras propiedades.
     * @param ctx
     */
    public SkeletonOverlayView(Context ctx) {
        super(ctx); init();
    }
    public SkeletonOverlayView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs); init();
    }
    public SkeletonOverlayView(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle); init();
    }

    /**
     * Inicializa las pinturas y otras propiedades.
     */
    private void init() {
        // Orange points – same palette as OverlayView.kt
        pointPaint.setColor(Color.parseColor("#dc965a"));
        pointPaint.setStyle(Paint.Style.FILL);

        // Green lines
        linePaint.setColor(Color.parseColor("#4CAF50"));
        linePaint.setStyle(Paint.Style.STROKE);

        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
    }

    /**
     * Actualiza las dimensiones del video. Esto es necesario para que la superposición se escale correctamente
     * incluso si el video tiene una relación de aspecto diferente a la del contenedor.
     * 
     * @param w
     * @param h
     */
    public void setVideoSize(int w, int h) {
        videoWidth  = w;
        videoHeight = h;
        computeBounds();
        invalidate();
    }

    /**
     * Actualiza los puntos de referencia mostrados en este frame y (opcionalmente) las dimensiones del video.
     * Pasa null para limpiar la superposición.
     *
     * @param lms
     * @param vw
     * @param vh
     */
    public void setLandmarks(List<NormalizedLandmark> lms, int vw, int vh) {
        landmarks = lms;
        if (vw != videoWidth || vh != videoHeight) {
            videoWidth  = vw;
            videoHeight = vh;
            computeBounds();
        }
        invalidate();
    }

    /**
     * Cuando el tamaño de la vista cambia (por ejemplo, al rotar el dispositivo), recalcula los límites de dibujo
     * para mantener el esqueleto alineado con el contenido del video.
     * @param w
     * @param h
     * @param oldw
     * @param oldh
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeBounds();
    }

    /**
     * Calcula los límites de dibujo (drawLeft, drawTop, drawRight, drawBottom) para que el esqueleto se escale y centre
     * correctamente dentro de esta vista, respetando la relación de aspecto del video. Esto replica el comportamiento de escala 
     * fitCenter utilizado por VideoView.
     */
    private void computeBounds() {
        float vw = getWidth(), vh = getHeight();
        if (vw == 0 || vh == 0 || videoWidth == 0 || videoHeight == 0) {
            drawLeft = 0; drawTop = 0; drawRight = vw; drawBottom = vh;
            return;
        }
        float scale = Math.min(vw / videoWidth, vh / videoHeight);
        float cw = videoWidth  * scale;
        float ch = videoHeight * scale;
        drawLeft   = (vw - cw) / 2f;
        drawTop    = (vh - ch) / 2f;
        drawRight  = drawLeft + cw;
        drawBottom = drawTop  + ch;
    }

    /**
     * Dibuja el esqueleto en el lienzo. Primero dibuja las conexiones entre los puntos de referencia utilizando linePaint,
     * luego dibuja los puntos de referencia individuales utilizando pointPaint. Las coordenadas de los puntos de referencia se escalan y 
     * traducen para que se alineen con el área de contenido del video dentro de esta vista.
     * 
     * @param canvas
     */
    @Override
    protected void onDraw(Canvas canvas) {
        if (landmarks == null || landmarks.isEmpty()) return;

        float cw = drawRight  - drawLeft;
        float ch = drawBottom - drawTop;

        float ptRadius = Math.max(5f, cw / 90f);
        linePaint.setStrokeWidth(Math.max(2f, cw / 180f));

        for (var conn : PoseLandmarker.POSE_LANDMARKS) {
            int s = conn.start(), e = conn.end();
            if (s >= landmarks.size() || e >= landmarks.size()) continue;
            NormalizedLandmark ls = landmarks.get(s);
            NormalizedLandmark le = landmarks.get(e);
            canvas.drawLine(
                    drawLeft + ls.x() * cw, drawTop + ls.y() * ch,
                    drawLeft + le.x() * cw, drawTop + le.y() * ch,
                    linePaint
            );
        }

        for (NormalizedLandmark lm : landmarks) {
            canvas.drawCircle(
                    drawLeft + lm.x() * cw,
                    drawTop  + lm.y() * ch,
                    ptRadius, pointPaint
            );
        }
    }
}
