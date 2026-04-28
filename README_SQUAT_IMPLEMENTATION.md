# RIZE Squat Biomechanics - Documentación de Implementación

## 🎯 Resumen

Se implementó análisis biomecánico completo de sentadilla en la app RIZE, sin tocar la lógica existente de curl. La implementación incluye:

- ✅ Cálculo automático de ángulos articulares (rodilla, cadera)
- ✅ Detección de repeticiones y fases (excéntrica/concéntrica)
- ✅ Métricas de fatiga (VL20 - pérdida de velocidad)
- ✅ Estabilidad técnica (CVT - Coeficiente de Variación Técnica)
- ✅ Alertas de errores técnicos en tiempo real
- ✅ UI dinámica que cambia según el ejercicio

---

## 📋 Especificación de Parámetros

### 1. Ángulo de Rodilla (θ_k)

**Fórmula**: Ángulo entre cadera-rodilla-tobillo

**Rangos de profundidad (runtime)**:
- **Parcial/Superficial**: θ_k ≥ 90° (flexión ligera)
- **Media**: 70° ≤ θ_k < 90° (muslo paralelo)
- **Profunda/Total**: θ_k < 70° (objetivo técnico ideal ≈ 45° o menos)

**Criterio de error**: `depthInsufficient = true` si θ_k > 45° en punto más bajo

**Rango esperado en datos reales**: 40.10° a 95.20°, media 59.75°

### 2. Ángulo de Cadera (θ_h)

**Fórmula**: Ángulo entre hombro-cadera-rodilla

**Valores típicos en sentadilla profunda**: 80° - 50°

**Criterio de riesgo (runtime)**: `trunkLeanRisk = true` si θ_h < 80° (excesiva inclinación forward)

### 3. Velocidad Angular de Rodilla (ω_k)

**Unidades**: grados/segundo (°/s)

**Fase excéntrica (bajada - descendente)**:
- Rango esperado: 60 - 120 °/s
- Tipo: velocidad negativa (ángulo disminuye)

**Fase concéntrica (subida - ascendente)**:
- Rango esperado: 80 - 160 °/s
- Tipo: velocidad positiva (ángulo aumenta)

### 4. Fatiga Técnica - VL20 (Velocity Loss at 20%)

**Cálculo**:
```
Fatiga_velocidad = ((V_ref - V_rep) / V_ref) × 100

donde:
  V_ref = velocidad máxima concéntrica en rep 1
  V_rep = velocidad máxima concéntrica en rep actual
```

**Criterios**:
- **< 10%**: Ejecución estable
- **10 - 20%**: Inicio de fatiga
- **≥ 20%**: Fatiga técnica significativa → ALERTA

**Umbral VL20**: 20% es el límite recomendado (maximiza adaptaciones de fuerza explosiva sin agotamiento neuromuscular)

### 5. Estabilidad Angular - CVT (Coeficiente de Variación Técnica)

**Cálculo**:
```
CVT = (σ_θ / μ_θ) × 100

donde:
  σ_θ = desviación estándar del ángulo mínimo entre reps
  μ_θ = media del ángulo mínimo
```

**Criterios**:
- **< 5%**: Ejecución consistente (CVT base ≈ 3.89% en condiciones controladas)
- **5 - 10%**: Variabilidad moderada
- **> 10%**: Inestabilidad técnica → Pérdida de control

---

## 🗂️ Archivos Modificados y Creados

### Creados

| Archivo | Descripción |
|---------|-------------|
| `app/src/main/kotlin/.../SquatBiomechanicsAlgorithm.kt` | Algoritmo principal de sentadilla |
| `app/src/test/java/.../SquatBiomechanicsAlgorithmTest.kt` | Pruebas unitarias |
| `POSICION_SENTADILLA.md` | Guía de posicionamiento correcto |

### Modificados

| Archivo | Cambios |
|---------|---------|
| `Algorithms.kt` | Activado `SquatBiomechanicsAlgorithm`, selector dinámico |
| `AlgorithmResult` | Campos para squat (knee/hip angles, velocity loss, CVT, rep count, flags) |
| `CameraActivity.java` | Detección de ejercicio (squat vs curl), UI dinámica |
| `activity_camera.xml` | IDs para labels dinámicos |
| `strings.xml` | Textos de UI específicos para squat |

---

## 🎮 Flujo de Datos

```
CameraView (MediaPipe Pose)
    ↓
PoseDataManager.sendPoseData(landmarkFlatList)
    ↓
CameraActivity.algorithms.onPoseData()
    ↓
Algorithms.selectAlgorithm("Barbell Squat")
    ├→ CurlBiomechanicsAlgorithm (si "curl")
    └→ SquatBiomechanicsAlgorithm (si "squat")
    ↓
AlgorithmResult { 
    kneeAngleDeg, 
    hipAngleDeg, 
    velocityLossPercent, 
    cvtPercent, 
    depthInsufficient,
    trunkLeanRisk,
    ... 
}
    ↓
CameraActivity.onSquatResult() (para squat)
    ├→ UI: Knee Angle
    ├→ UI: Technical CVT (% con color)
    ├→ UI: Velocity Retention (% con progreso)
    └→ Alertas técnicas
```

---

## 📱 UI Actualizada para Barbell Squat

### Tarjeta 1: Knee Angle

- **Label**: "KNEE ANGLE" (cambia de "PEAK ANGLE")
- **Valor**: θ_k en grados (0.0°)
- **Color**: Dinámico según θ_h (rojo si riesgo de tronco)

### Tarjeta 2: Technical CVT

- **Label**: "TECHNICAL CVT" (cambia de "STABILITY")
- **Valor**: CVT en porcentaje
- **Color**:
  - 🟢 Verde: CVT < 5% (consistente)
  - 🟠 Naranja: 5% ≤ CVT ≤ 10% (moderado)
  - 🔴 Rojo: CVT > 10% (inestable)

### Barra de Progreso: Velocity Retention

- **Label**: "Velocity Retention VL20 Fatigue Threshold"
- **Rango**: 0 - 100%
  - 100% = V_rep = V_ref (sin fatiga)
  - 0% = pérdida total
- **Progreso**: (100 - VelocityLossPercent)

---

## 🔬 Algoritmo de Detección de Reps

### Máquina de Estados

```
┌─────────────────────────────────────────────┐
│ IDLE (reposo)                               │
│ - Espera θ_k < 165° + velocidad negativa   │
└─────────────────────────────────────────────┘
         ↓ (inicia descenso)
┌─────────────────────────────────────────────┐
│ DESCENT (bajada/excéntrica)                 │
│ - Rastrea θ_k mínimo                        │
│ - Calcula ω_k negativa máxima               │
└─────────────────────────────────────────────┘
         ↓ (velocidad positiva)
┌─────────────────────────────────────────────┐
│ ASCENT (subida/concéntrica)                 │
│ - Rastrea θ_k y calcula ω_k positiva máxima │
│ - Cierre por top (θ_k ≥ 148°) o meseta      │
└─────────────────────────────────────────────┘
         ↓ (vuelve a IDLE)
```

---

## 🧪 Pruebas Unitarias

### Test 1: Detección de Fatiga VL20

```kotlin
fun detectsFatigueWhenConcentricVelocityDropsMoreThan20Percent()
```

- **Entrada**: Rep 1 rápida (20 frames subida), Rep 2 lenta (45 frames subida)
- **Esperado**: `fatigueDetected = true`, `velocityLossPercent > 20%`
- **Resultado**: ✅ PASS

### Test 2: Profundidad Insuficiente + Riesgo de Tronco

```kotlin
fun flagsDepthAndTrunkRiskOnShallowAndLeaningRep()
```

- **Entrada**: θ_k mínimo = 60° (> 45°), θ_h mínimo = 70° (< 80°)
- **Esperado**: `depthInsufficient = true`, `trunkLeanRisk = true`
- **Resultado**: ✅ PASS

---

## 🚀 Uso en App

### Iniciar Sesión de Sentadilla

1. Usuario selecciona "Barbell Squat" en `SelectActivity`
2. `CameraActivity` recibe `exercise_name = "Barbell Squat"`
3. `setupAlgorithms()` detecta "squat" → activa `SquatBiomechanicsAlgorithm`
4. UI cambia a métricas de sentadilla

### Durante la Sesión

- Frame a frame: cálculo de θ_k, θ_h, ω_k
- Detección automática de reps
- Acumulación de datos para CVT
- Alertas en tiempo real si fatiga (VL20) o errores técnicos

### Al Finalizar

- Resumen: total de reps, CVT final, pérdida máxima de velocidad
- Alertas registradas: profundidad, tronco, fatiga
- Datos listos para `SummaryActivity`

---

## 📊 Métricas Esperadas (Datos Reales)

Basado en el documento especificación:

| Métrica | Rango | Media | Notas |
|---------|-------|-------|-------|
| θ_k (rodilla) | 40.1° - 95.2° | 59.75° | Variabilidad considerable |
| θ_h (cadera) | 50° - 80° | ~65° | Profundidad profunda típica |
| ω_k excéntrica | 60 - 120 °/s | ~90 °/s | Controlada |
| ω_k concéntrica | 80 - 160 °/s | ~120 °/s | Explosiva |
| CVT (estable) | < 5% | ~3.89% | Controladas |
| VL20 (sin fatiga) | < 10% | ~5% | Ejecución óptima |

---

## ⚠️ Limitaciones y Consideraciones

1. **Posición de cámara**: Debe estar lateral (de lado). Ver `POSICION_SENTADILLA.md`
2. **Iluminación**: Mejor con luz frontal o lateral
3. **Ropa**: Preferir ajustada para mejor detección articular
4. **MediaPipe**: Dependencia de visibilidad de landmarks (MIN_VISIBILITY = 0.6)
5. **Precisión angular**: ±2-3° de error natural en estimación de pose

---

## 🔧 Parámetros Ajustables

En `SquatBiomechanicsAlgorithm.kt`:

```kotlin
// Umbral de profundidad
private const val DEPTH_ERROR_THRESHOLD = 45.0  // grados

// Riesgo de tronco
private const val TRUNK_RISK_THRESHOLD = 80.0   // grados

// Histeresis de velocidad (cambio de fase)
private const val VELOCITY_HYSTERESIS = 6.5     // °/s

// Umbral de fatiga
private const val CONCENTRIC_FATIGUE_THRESHOLD = 20.0  // % VL20
```

Estos pueden ajustarse según validación con usuarios reales.

---

## ✅ Checklist de Integración

- [x] Algoritmo de sentadilla implementado
- [x] Enrutamiento dinámico en `Algorithms.kt`
- [x] UI dinámica por ejercicio
- [x] Pruebas unitarias
- [x] Documentación de posición
- [x] Curl intacto (sin cambios)
- [x] Build exitoso sin errores

---

## 📞 Próximos Pasos

1. **Validación con videos reales**: Ajustar umbrales con datos de usuarios
2. **Alertas en pantalla**: Agregar pop-ups/toasts de errores técnicos
3. **Historial de reps**: Guardar datos de cada rep en BD
4. **Comparación inter-series**: Mostrar degradación de técnica entre sets
5. **Exportar datos**: CSV/JSON con métricas por sesión

---

## 📖 Referencias

- Serbest, Ş., et al. (2022). "A Biomechanical Analysis of Dumbbell Curl..."
- Documento interno: "Medición de variables cinemáticas" (§1-§9)
- MediaPipe Pose: https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker

---

**Última actualización**: 2026-04-04
**Estado**: ✅ Implementación Completa - Listo para Pruebas

