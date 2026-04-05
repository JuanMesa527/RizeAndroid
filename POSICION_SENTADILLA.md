# 📍 Posición Correcta para Análisis de Sentadilla en RIZE

## Respuesta Directa: **DE LADO** (Vista Lateral/Sagital)

La persona debe estar **completamente de lado** frente a la cámara para que el análisis sea preciso.

---

## ¿Por Qué DE LADO?

### Ángulos Que Medimos

El algoritmo calcula dos ángulos fundamentales en el plano **sagital (lateral)**:

| Ángulo | Puntos | Por qué DE LADO |
|--------|--------|-----------------|
| **θ_k (Rodilla)** | cadera → rodilla → tobillo | Ver flexión real de rodilla |
| **θ_h (Cadera)** | hombro → cadera → rodilla | Detectar inclinación del tronco |

En vista lateral, estos ángulos se proyectan correctamente. De frente, se distorsionan.

---

## 🎬 Setup Correcto

```
        📱 CÁMARA
        |
        | ← 1.5 - 2 metros
        |
        ↓
       👤 ← PERSONA (perfil derecho O izquierdo)
       
       VISTA LATERAL: 90° entre cámara y persona
```

### Checklist

- ✅ **Posición**: Perfil derecho o izquierdo (90° a cámara)
- ✅ **Distancia**: 1.5 - 2 metros
- ✅ **Visibilidad**: Hombro, cadera, rodilla, tobillo claros
- ✅ **Iluminación**: Luz frontal o lateral (evita contraluz)
- ✅ **Ropa**: Preferir ropa ajustada para mejor detección articular

---

## ❌ NO Hacer

| Posición | Problema |
|----------|----------|
| **De frente** | Ángulos distorsionados, profundidad no confiable |
| **En diagonal** | Mezcla de frente y lado, pérdida de precisión |
| **Muy cerca** | Cuerpo se corta en pantalla, puntos perdidos |
| **Muy lejos** | MediaPipe no ve detalles, visibilidad baja |

---

## 📊 Ejemplo de Posiciones

### ✅ CORRECTA (Lateral Derecha)

```
    HOMBRO
       |
    CADERA
       |
    RODILLA
       |
    TOBILLO
    
    ← CÁMARA
```

Los 4 puntos quedan en línea vertical clara (o casi vertical con ligera inclinación de tronco).

### ❌ INCORRECTA (Frontal)

```
  HOMBRO_IZQ   HOMBRO_DER
       |            |
    CADERA_IZQ   CADERA_DER
       |            |
    RODILLA_IZQ  RODILLA_DER
       |            |
    TOBILLO_IZQ  TOBILLO_DER
    
    ← CÁMARA
```

Los puntos están separados horizontalmente, no en plano sagital.

---

## 🎯 Recomendaciones

1. **Primero entrena sin cámara** para entender la posición
2. **Mira el espejo lateral** antes de grabar
3. **Comprueba que todo el cuerpo sea visible** en pantalla
4. **Siempre de LADO**: Es el estándar en biomecánica deportiva
5. **Si necesitas cambiar de lado** (izquierdo a derecho), hazlo entre series, no durante

---

## 📱 En la App

- La cámara detectará automáticamente qué lado tiene mejor visibilidad
- Si la posición no es óptima (frontal/diagonal), los ángulos serán imprecisos
- Verás ángulos extraños → revisa que estés completamente DE LADO

---

## 🧮 Métricas Que Mejorarán

Con posición lateral correcta:

- ✅ **Ángulo de rodilla**: ±2° de error (vs ±15° en frontal)
- ✅ **Profundidad de sentadilla**: Detección confiable
- ✅ **Inclinación de tronco**: Medible
- ✅ **Velocidad angular**: Precisa
- ✅ **Fatiga (VL20)**: Comparaciones justas entre reps

---

## 💡 Ejemplo en Video

Si grabas una sentadilla:
- **DE LADO**: Ves el movimiento natural, completo, con transición fluida
- **DE FRENTE**: Ves ambas piernas moviéndose, pero los ángulos no son el plano de movimiento real

**Resumiendo**: Para análisis de sentadilla en RIZE, **siéntate DE LADO a la cámara** como si estuvieras en el espejo del gimnasio viendo tu perfil lateral. ✅

