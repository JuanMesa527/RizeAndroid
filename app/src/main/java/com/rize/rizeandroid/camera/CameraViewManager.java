package com.rize.rizeandroid.camera;

import android.content.Context;
import android.widget.FrameLayout;

import androidx.lifecycle.LifecycleOwner;

/**
 * Gestiona el ciclo de vida de CameraView y proporciona métodos para iniciar, cambiar y liberar la cámara.
 */
public class CameraViewManager {

    private final CameraView cameraView;
    private final FrameLayout container;

    /**
     * Inicializa el CameraViewManager con los parámetros especificados.
     *
     * @param context           El contexto de la aplicación.
     * @param lifecycleOwner    El encargado del ciclo de vida para gestionar el ciclo de vida de la cámara.
     * @param container         La vista contenedora para alojar la vista previa de la cámara.
     * @param startFrontCamera  Si se debe iniciar con la cámara frontal.
     */
    public CameraViewManager(Context context, LifecycleOwner lifecycleOwner, FrameLayout container, boolean startFrontCamera) {
        this.container = container;
        this.cameraView = new CameraView(context, lifecycleOwner, startFrontCamera);
    }

    /**
     * Inicia la cámara y muestra la vista previa en el contenedor.
     */
    public void start() {
        container.removeAllViews();
        container.addView(cameraView.getView());
        cameraView.startCamera();
    }

    /**
     * Cambia la cámara utilizada para la vista previa.
     */
    public void switchCamera() {
        cameraView.switchCamera();
    }

    /**
     * Libera los recursos de la cámara.
     */
    public void release() {
        cameraView.release();
    }
}
