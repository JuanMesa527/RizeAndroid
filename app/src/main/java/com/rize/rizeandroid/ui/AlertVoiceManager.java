package com.rize.rizeandroid.ui;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Manejador de alertas de voz para proporcionar retroalimentación auditiva al usuario durante los ejercicios. */
public class AlertVoiceManager {

    private static final String TAG             = "AlertVoiceManager";
    private static final long   DEBOUNCE_MS     = 1_000;
    private static final long   COOLDOWN_RED_MS = 8_000;
    private static final long   COOLDOWN_AMB_MS = 12_000;

    public static final int SLOT_SQUAT  = 0;
    public static final int SLOT_BENCH  = 1;
    public static final int SLOT_CURL   = 2;
    private static final int SLOT_COUNT = 3;

    private static final String[] SILENT_PREFIXES = {
        "Calibrando", "Alineate", "Levanta el peso",
        "Posicionate", "Tecnica estable", "Técnica estable", "Rep "
    };

    private static final Map<String, String> VOICE_MAP = new HashMap<>();
    private static final Map<String, String> VOICE_PREFIXES = new LinkedHashMap<>();
    private static final Map<String, String> EXTREME_MAP = new HashMap<>();

    static {
        VOICE_MAP.put("Profundidad insuficiente: baja un poco mas.",
                "Baja más en la sentadilla, no llegas a la profundidad correcta");
        VOICE_MAP.put("Profundidad parcial: baja mas (objetivo menor a 80°).",
                "Profundidad muy parcial, baja mucho más");
        VOICE_MAP.put("Profundidad media: buena, pero puedes bajar un poco mas.",
                "Casi llegas, baja un poco más para completar la profundidad");
        VOICE_MAP.put("Tronco muy inclinado: eleva el pecho.",
                "Eleva el pecho, tu tronco está demasiado inclinado");
        VOICE_MAP.put("Tronco demasiado inclinado: enderezate un poco.",
                "Enderézate, el tronco está muy inclinado hacia adelante");
        VOICE_MAP.put("Tronco muy vertical: inclinate ligeramente hacia delante.",
                "Inclínate ligeramente hacia adelante con el tronco");
        VOICE_MAP.put("Corrige profundidad y tronco en esta repeticion.",
                "Corrige la profundidad y la posición del tronco");
        VOICE_MAP.put("Inestabilidad tecnica: reduce variabilidad.",
                "Mucha variabilidad, enfócate en repeticiones consistentes");
        VOICE_MAP.put("Variabilidad moderada: busca reps consistentes.",
                "Intenta hacer las repeticiones más consistentes entre sí");
        VOICE_MAP.put("Fatiga tecnica (VL20): considera cerrar la serie.",
                "Estás fatigado, considera terminar la serie");

        VOICE_MAP.put("Agarre excesivo: riesgo de hombro, acerca las manos.",
                "Agarre demasiado ancho, acerca las manos para proteger el hombro");
        VOICE_MAP.put("Agarre demasiado estrecho: separa un poco las manos.",
                "Agarre muy estrecho, separa un poco las manos");
        VOICE_MAP.put("Agarre ancho: ideal cerca de 1.5x el ancho de hombros.",
                "Ajusta el agarre a un ancho y medio de hombros");
        VOICE_MAP.put("Abduccion fuera de rango: manten 45°-85°; bajo 80° de codo, hombro ≤55°.",
                "El ángulo del hombro está fuera de rango, cierra un poco los codos");
        VOICE_MAP.put("Abduccion critica (>90): riesgo de lesion en hombro.",
                "Peligro, el hombro está muy abierto, riesgo de lesión");
        VOICE_MAP.put("Asimetria bilateral detectada: iguala ambos codos.",
                "Tus dos brazos no están simétricos, iguala la altura de ambos codos");
        VOICE_MAP.put("Profundidad insuficiente: baja hasta el umbral de conteo (codo ≤85°).",
                "Baja más la barra, no llegas a la profundidad correcta");
        VOICE_MAP.put("Extension incompleta: sube hasta el umbral de conteo (codo ≥160°).",
                "Extiende completamente los brazos al subir");
        VOICE_MAP.put("Periodo de estancamiento detectado: considera asistencia.",
                "Estancamiento detectado, pide asistencia");
        VOICE_MAP.put("Fatiga moderada (VL25): monitorea la tecnica.",
                "Fatiga moderada, mantén atención en la técnica");
        VOICE_MAP.put("Fatiga critica (VL35): considera cerrar la serie.",
                "Fatiga crítica, considera terminar la serie");
        VOICE_MAP.put("Corrige profundidad y extension en esta repeticion.",
                "Corrige la profundidad y la extensión en esta repetición");

        VOICE_PREFIXES.put("Ángulo: codo separado",
                "Pega el codo al costado, está demasiado separado del cuerpo");
        VOICE_PREFIXES.put("Velocidad: caída",
                "Caída de velocidad significativa, considera terminar la serie");
        VOICE_PREFIXES.put("Flexión: ROM",
                "El recorrido es insuficiente, baja y sube el brazo completamente");

        EXTREME_MAP.put("Sin Detección",
                "No te detecto en la cámara, reposiciónate");
        EXTREME_MAP.put("Periodo de estancamiento",
                "Estancamiento detectado, pide asistencia");
        EXTREME_MAP.put("Riesgo de Lesión",
                "Riesgo de lesión, detente y corrige la técnica antes de continuar");
    }

    public enum Severity { NONE, AMBER, RED }

    private final String[]   pendingText  = new String[SLOT_COUNT];
    private final long[]     pendingSince = new long[SLOT_COUNT];
    private final String[]   lastSpoken   = new String[SLOT_COUNT];
    private final long[]     lastSpokenAt = new long[SLOT_COUNT];
    private final Severity[] lastSev      = new Severity[SLOT_COUNT];

    private TextToSpeech tts;
    private boolean      ready  = false;
    private boolean      paused = false;
    private final Handler mainHandler;

    /**
     * Inicializa el AlertVoiceManager, configurando el TextToSpeech y preparando las estructuras para manejar las alertas de voz.
     * @param ctx
     */
    public AlertVoiceManager(Context ctx) {
        mainHandler = new Handler(Looper.getMainLooper());
        for (int i = 0; i < SLOT_COUNT; i++) {
            pendingText[i] = null; pendingSince[i] = 0;
            lastSpoken[i]  = "";   lastSpokenAt[i] = 0;
            lastSev[i]     = Severity.NONE;
        }
        tts = new TextToSpeech(ctx.getApplicationContext(), status -> mainHandler.post(() -> {
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS init failed: " + status);
                return;
            }
            int r = tts.setLanguage(new Locale("es", "MX"));
            if (r < 0) r = tts.setLanguage(new Locale("es", "ES"));
            if (r < 0) tts.setLanguage(Locale.getDefault());
            tts.setSpeechRate(0.85f);
            ready = true;
            Log.d(TAG, "TTS ready");
        }));
    }

    /**
     * Maneja las actualizaciones de las alertas de voz, aplicando lógica de debounce y cooldown para evitar repeticiones excesivas, 
     * y utilizando mapeos para convertir alertas técnicas en mensajes más amigables para el usuario.
     * 
     * @param slot
     * @param text
     * @param sev
     */
    public void onBannerUpdate(int slot, String text, Severity sev) {
        if (!ready || paused || tts == null) return;
        if (sev == Severity.NONE || text == null || text.isEmpty() || isSilent(text)) {
            pendingText[slot] = null;
            return;
        }
        long now = System.currentTimeMillis();
        if (!text.equals(pendingText[slot])) {
            pendingText[slot] = text;
            pendingSince[slot] = now;
            return;
        }
        if (now - pendingSince[slot] < DEBOUNCE_MS) return;
        long cooldown = sev == Severity.RED ? COOLDOWN_RED_MS : COOLDOWN_AMB_MS;
        if (text.equals(lastSpoken[slot]) && now - lastSpokenAt[slot] < cooldown) return;
        doSpeak(text, sev, slot);
    }

/**
 * Maneja alertas extremas que requieren una atención inmediata del usuario, utilizando un mapeo específico para convertir títulos de 
 * alertas en mensajes de voz claros y directos.
 * 
 * @param title
 * @param message
 */
    public void onExtremeAlert(String title, String message) {
        if (!ready || paused || tts == null) return;
        String voice = EXTREME_MAP.get(title);
        if (voice == null) {
            voice = (message != null && !message.isEmpty())
                    ? title + ". " + firstSentence(message)
                    : title;
        }
        Bundle b = new Bundle();
        b.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "extreme");
        tts.speak(voice, TextToSpeech.QUEUE_FLUSH, b, "extreme");
    }

    /**
     * Pausa la reproducción de alertas de voz.
     */
    public void onPause() {
        paused = true;
        if (ready && tts != null && tts.isSpeaking()) tts.stop();
        for (int i = 0; i < SLOT_COUNT; i++) pendingText[i] = null;
    }

    /**
     * Reanuda la reproducción de alertas de voz.
     */
    public void onResume() {
        paused = false;
    }

    /**
     * Destruye el AlertVoiceManager y libera los recursos del motor de TextToSpeech.
     */
    public void destroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        ready = false;
    }

    /**
     * Realiza la síntesis de voz para el texto proporcionado, aplicando lógica para evitar repeticiones excesivas y utilizando 
     * mapeos para convertir alertas técnicas en mensajes más amigables para el usuario.
     * 
     * @param text
     * @param sev
     * @param slot
     */
    private void doSpeak(String text, Severity sev, int slot) {
        String voice = resolveVoiceText(text);
        if (tts.isSpeaking()) {
            Severity highest = Severity.NONE;
            for (Severity s : lastSev) if (s != null && s.ordinal() > highest.ordinal()) highest = s;
            if (sev.ordinal() < highest.ordinal()) return;
            tts.stop();
        }
        Bundle b = new Bundle();
        String uid = "slot_" + slot;
        b.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uid);
        tts.speak(voice, TextToSpeech.QUEUE_FLUSH, b, uid);
        lastSpoken[slot]   = text;   
        lastSpokenAt[slot] = System.currentTimeMillis();
        lastSev[slot]      = sev;
    }

    /**
     * Resuelve el texto de la alerta utilizando mapeos para convertir mensajes técnicos en mensajes de voz más amigables para el usuario,
     * y aplicando prefijos para manejar casos específicos de alertas que comienzan con ciertos patrones.
     * 
     * @param text
     * @return
     */
    private String resolveVoiceText(String text) {
        String mapped = VOICE_MAP.get(text);
        if (mapped != null) return mapped;
        for (Map.Entry<String, String> entry : VOICE_PREFIXES.entrySet()) {
            if (text.startsWith(entry.getKey())) return entry.getValue();
        }
        return text;
    }

    /**
     * Determina si el texto de la alerta debe ser considerado silencioso, es decir, si comienza con ciertos prefijos que 
     * indican que no se debe reproducir una alerta de voz para ese mensaje.
     * 
     * @param t
     * @return
     */
    private boolean isSilent(String t) {
        for (String p : SILENT_PREFIXES) if (t.startsWith(p)) return true;
        return false;
    }

    /**
     * Extrae la primera oración de un mensaje, utilizando el primer punto como delimitador, para proporcionar mensajes de voz 
     * más concisos y claros al usuario.
     * @param s
     * @return
     */
    private String firstSentence(String s) {
        int i = s.indexOf('.');
        return i > 0 ? s.substring(0, i + 1) : s;
    }
}
