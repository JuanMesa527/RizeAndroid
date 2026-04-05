# 🧪 Guía de Testing: Sentadilla en RIZE

## 📝 Pasos para Validar la Implementación

### 1. Verificar que Curl Sigue Funcionando

```
[ ] Abrir app
[ ] Ir a SelectActivity
[ ] Seleccionar "Dumbbell Curl"
[ ] Entrar a CameraActivity
[ ] Verificar UI:
    - "PEAK ANGLE" (NO "KNEE ANGLE")
    - "STABILITY" (NO "TECHNICAL CVT")
    - "Session Consistency" (NO "Velocity Retention")
[ ] Hacer movimiento de curl
[ ] Verificar que el ángulo cambia (brazos flexionándose)
[ ] ✅ Si funciona igual que antes → Curl intacto ✓
```

---

### 2. Verificar Detección de Sentadilla

```
[ ] Abrir app
[ ] Ir a SelectActivity
[ ] Seleccionar "Barbell Squat"
[ ] Entrar a CameraActivity
[ ] Verificar UI ha cambiado:
    - "KNEE ANGLE" (antes era "PEAK ANGLE")
    - "TECHNICAL CVT" (antes era "STABILITY")
    - "Velocity Retention" (antes era "Session Consistency")
[ ] ✅ Si se cambió → Detección correcta ✓
```

---

### 3. Test de Posición: DE LADO

```
SETUP:
[ ] Posiciónate DE LADO a la cámara (perfil lateral)
[ ] Distancia: 1.5-2 metros
[ ] Cuerpo completo visible (hombro hasta tobillo)
[ ] Buena iluminación

DURANTE LA SESIÓN:
[ ] Haz 5 sentadillas lentas y controladas
[ ] Observa en la UI:
    - Knee Angle (θ_k): debería estar entre 40° y 170°
    - Technical CVT: debería ser bajo en las primeras reps (<5%)
    - Velocity Retention: 100% en la primera rep

VALIDACIÓN:
[ ] Ángulos dentro de rango realista (40°-170°)
[ ] CVT aumenta ligeramente con cada rep (consistencia)
[ ] Valores lógicos y no saltan drásticamente
[ ] ✅ Si sí → Posición correcta ✓
```

---

### 4. Test de Profundidad

```
SENTADILLA PROFUNDA (ATG - Ass to Grass):
[ ] Posición: DE LADO
[ ] Baja hasta que muslos estén paralelos o más bajo (θ_k ≈ 45°-50°)
[ ] Sube lentamente
[ ] Observa: ¿Depthinsufficient = false?
[ ] ✅ Si NO aparece alerta → Profundidad detectada ✓

SENTADILLA PARCIAL (Superficial):
[ ] Posición: DE LADO
[ ] Solo dobla rodillas 20-30° (θ_k ≈ 140°-150°)
[ ] Sube
[ ] Observa: ¿aparece indicador de "Profundidad insuficiente"?
[ ] ✅ Si aparece → Error técnico detectado ✓
```

---

### 5. Test de Fatiga (VL20)

```
ENTRENA CON FATIGA:
[ ] Haz 10-12 sentadillas seguidas (sin descanso)
[ ] Observa "Velocity Retention" en tiempo real
[ ] Primera rep: 100% (referencia)
[ ] Reps 2-5: debería estar 95-100% (estable)
[ ] Reps 6-8: puede bajar a 85-95% (leve fatiga)
[ ] Reps 9+: debería bajar a <80% si hay fatiga real

VALIDACIÓN:
[ ] Si Velocity < 80% → aparecerá alerta de "FATIGA TÉCNICA"
[ ] El porcentaje es consistente con tu sensación de cansancio
[ ] CVT aumenta también (inestabilidad por fatiga)
[ ] ✅ Si sí → Detección de fatiga correcta ✓
```

---

### 6. Test de Inclinación de Tronco

```
SENTADILLA CON TRONCO ERGUIDO:
[ ] Posición: DE LADO
[ ] Mantén tronco vertical (hombros sobre caderas)
[ ] θ_h debería estar alrededor de 80-100°
[ ] Observa: NO debería haber alerta de tronco
[ ] ✅ Si no hay alerta → Correcta ✓

SENTADILLA CON TRONCO INCLINADO:
[ ] Posición: DE LADO
[ ] Inclínate hacia adelante deliberadamente
[ ] θ_h debería bajar a 50-70° (<80°)
[ ] Observa: ¿Aparece indicador de riesgo de tronco?
[ ] ✅ Si aparece → Error de inclinación detectado ✓
```

---

### 7. Test de Estabilidad (CVT)

```
PRIMERA SERIE (consistente):
[ ] Haz 5 sentadillas con la MISMA profundidad
[ ] Cada rep baja a θ_k ≈ 60° (±2°)
[ ] CVT debería ser < 5% (verde)
[ ] ✅ Si CVT < 5% → Consistencia perfecta ✓

SEGUNDA SERIE (menos consistente):
[ ] Haz 5 sentadillas con PROFUNDIDADES VARIABLES
[ ] Rep 1: 50°, Rep 2: 60°, Rep 3: 55°, etc.
[ ] CVT debería subir a 8-12% (naranja/rojo)
[ ] ✅ Si CVT aumenta → Variabilidad detectada ✓
```

---

### 8. Test de Posición Incorrecta (DE FRENTE)

```
COLÓCATE DE FRENTE:
[ ] Cara frente a la cámara
[ ] Hace sentadillas
[ ] Observa: ¿Ángulos raros? (>180°, negativos, inconsistentes)
[ ] ✅ Si ves valores raros → Posición de frente detectada ✓

NOTA: No es error de la app, es que necesita vista lateral.
```

---

### 9. Test de Cambio de Lado

```
PRIMERA MITAD DE SERIES:
[ ] Haz sentadillas de LADO DERECHO
[ ] Observa CVT, rep count, velocidad

SEGUNDA MITAD:
[ ] Gira 180° (ahora LADO IZQUIERDO)
[ ] Haz más sentadillas
[ ] CVT debería reiniciarse/ajustarse (nuevo lado = nuevo contexto)
[ ] ✅ Si app se adapta → Cambio de lado soportado ✓

RECOMENDACIÓN: Mejor usar mismo lado en una sesión
```

---

## 🔍 Métricas Esperadas

### Rango Normal de Ángulos

| Ángulo | Rango Esperado | Alarma si |
|--------|---|---|
| θ_k (rodilla) | 40° - 170° | > 170° (no dobla) o < 30° (demasiado) |
| θ_h (cadera) | 50° - 120° | < 50° (peligroso) o > 150° (posición inicial) |
| ω_k (velocidad) | ±60 a ±160 °/s | > ±200 (explosión anómala) |

### Métricas por Sesión

| Métrica | Rango Normal | Alerta |
|---------|---|---|
| CVT | 3-8% | > 10% (inestable) |
| VL20 | < 10% | ≥ 20% (fatiga) |
| Rep Count | 5-20 | Muy alto o muy bajo |

---

## 📊 Checklist Final de QA

- [ ] **Curl intacto**: "Peak Angle", "Stability", funcionan como antes
- [ ] **Squat detectado**: Labels cambian a "Knee Angle", "Technical CVT"
- [ ] **UI actualiza en tiempo real**: Valores cambian al moverse
- [ ] **Profundidad medible**: θ_k disminuye al bajar
- [ ] **Tronco detectable**: θ_h cambia con inclinación
- [ ] **Fatiga (VL20)**: Disminuye con reps consecutivas
- [ ] **Estabilidad (CVT)**: Aumenta con variabilidad
- [ ] **Alertas funcionales**: Colores cambian según umbral
- [ ] **Build exitoso**: `gradle build` sin errores
- [ ] **Pruebas pasan**: `testDebugUnitTest` exitoso
- [ ] **Posición lateral óptima**: Mejor con vista lateral

---

## 🐛 Posibles Issues y Soluciones

### Ángulos Extraños (> 180° o < 0°)

**Causa**: Probablemente de frente, no de lado
**Solución**: Gira 90° para estar completamente de lado

### CVT Siempre en 0%

**Causa**: Solo has hecho 1 rep, se necesitan 2+ para calcular
**Solución**: Haz al menos 3 reps completas antes de esperar CVT

### Velocity Retention Congelado en 100%

**Causa**: Primera rep de la serie, no hay punto de comparación
**Solución**: Normal en rep 1, baja después de rep 2+

### Ángulos No Cambian

**Causa**: MediaPipe no detecta los landmarks
**Solución**: Mejor iluminación, acércate más, ropa contraste

### App Cuelga

**Causa**: Carga computacional de pose estimation
**Solución**: Reduce framerate, reinicia app, libera memoria

---

## ✅ Criterios de Aceptación

### Mínimo Viable

- [x] Algoritmo funciona sin crashes
- [x] UI actualiza con datos de sentadilla
- [x] Curl sigue intacto
- [x] Build compila sin errores
- [x] Tests unitarios pasan

### Nice to Have

- [ ] Alertas sonoras al detectar errores
- [ ] Historial de reps guardado en BD
- [ ] Exportar datos a CSV
- [ ] Comparación visual reps 1 vs N
- [ ] Posición ideal sugerida por IA

---

## 📝 Plantilla de Reporte de Test

```
TEST: [Nombre del test]
RESULTADO: ✅ PASS / ❌ FAIL / ⚠️ WARN

Descripción:
- Paso 1: [resultado]
- Paso 2: [resultado]
- Paso 3: [resultado]

Notas:
[Observaciones adicionales]

Evidencia:
- Screenshot: [si aplica]
- Logs: [si aplica]
```

---

**Estado**: Listo para pruebas manuales en dispositivo físico

**Próximos pasos**:
1. Ejecutar tests unitarios ✅
2. Compilar apk de debug ✅
3. Instalar en dispositivo Android
4. Hacer pruebas manuales de cada test
5. Ajustar umbrales si es necesario
6. Documentar resultados

---

¿Preguntas sobre qué testar? Consulta `README_SQUAT_IMPLEMENTATION.md` para detalles técnicos. 🚀

