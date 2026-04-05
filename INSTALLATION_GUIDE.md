# 🚀 GUÍA DE INSTALACIÓN Y PUESTA EN MARCHA

## ✅ Pre-requisitos

- [x] Android Studio (última versión)
- [x] Android SDK 28+
- [x] Java JDK 11+
- [x] Dispositivo Android o emulador

---

## 📦 Instalación

### Paso 1: Compilar la App

```bash
cd C:\Users\Michael\AndroidStudioProjects\RizeAndroid

# Limpiar y compilar
.\gradlew.bat clean build

# Resultado esperado: BUILD SUCCESSFUL
```

### Paso 2: Instalar en Dispositivo

```bash
# Conectar dispositivo Android via USB (o emulador debe estar corriendo)

# Instalar APK de debug
.\gradlew.bat installDebug

# Resultado esperado: Success
```

### Paso 3: Ejecutar Tests

```bash
# Tests unitarios
.\gradlew.bat testDebugUnitTest

# Resultado esperado:
# ✓ detectsFatigueWhenConcentricVelocityDropsMoreThan20Percent
# ✓ flagsDepthAndTrunkRiskOnShallowAndLeaningRep
```

---

## 🎮 Usar la App

### Primera Vez: Curl (Verificar que no cambió)

1. Abre RIZE en el dispositivo
2. Ir a `SelectActivity` (pantalla de selección)
3. Selecciona **"Dumbbell Curl"**
4. Entra a `CameraActivity`
5. Verifica que diga:
   - ✅ "PEAK ANGLE" (no "KNEE ANGLE")
   - ✅ "STABILITY" (no "TECHNICAL CVT")
   - ✅ "Session Consistency" (no "Velocity Retention")
6. Hacer movimiento de curl
7. Ángulo debe cambiar (brazos flexionándose)

**Resultado**: Curl funciona como antes ✅

### Segunda Vez: Sentadilla (Nueva)

1. Vuelve a `SelectActivity`
2. Selecciona **"Barbell Squat"**
3. Entra a `CameraActivity`
4. Verifica que cambió a:
   - ✅ "KNEE ANGLE" (cambió)
   - ✅ "TECHNICAL CVT" (cambió)
   - ✅ "Velocity Retention" (cambió)
5. **Posiciónate DE LADO a la cámara** (IMPORTANTE)
   - Distancia: 1.5-2 metros
   - Cuerpo completo visible
   - Buena iluminación
6. Haz 5-10 sentadillas controladas
7. Observa valores en tiempo real:
   - Knee Angle: debe ir de 170° (arriba) a ~50° (abajo)
   - Technical CVT: debe ser bajo (<5%)
   - Velocity Retention: 100% en rep 1, baja en reps posteriores

**Resultado**: Sentadilla funciona ✅

---

## 🔧 Configuración Recomendada

### Cámara

```
- Resolución: 4:3 (configurada en CameraView.kt)
- FPS: 30 Hz (óptimo para pose estimation)
- Modo: Frontal (puede girar a trasera si lo deseas)
```

### Iluminación

```
- Luz frontal o lateral (no contraluz)
- Luz natural o LED blanco
- Evitar sombras
```

### Posición Óptima

```
CÁMARA 📱
  ↓
1.5-2 metros
  ↓
👤 DE LADO (perfil)
  ↕️
Hombro - Cadera - Rodilla - Tobillo (línea vertical)
```

---

## ⚙️ Parámetros Ajustables

Si necesitas cambiar sensibilidad de detección, edita `SquatBiomechanicsAlgorithm.kt`:

```kotlin
// Profundidad mínima (cambiar para hacer más/menos sensible)
private const val DEPTH_ERROR_THRESHOLD = 45.0  // grados

// Riesgo de tronco (cambiar para permitir más inclinación)
private const val TRUNK_RISK_THRESHOLD = 80.0   // grados

// Umbral de fatiga (cambiar para detectar fatiga más temprano/tarde)
private const val CONCENTRIC_FATIGUE_THRESHOLD = 20.0  // %
```

Después de cambiar, recompila:
```bash
.\gradlew.bat clean build
.\gradlew.bat installDebug
```

---

## 🧪 Testing Manual

### Test 1: Posición Correcta (DE LADO)

```
✓ Posiciónate DE LADO
✓ Haz 5 sentadillas
✓ Espera que:
  - Ángulos sean lógicos (40-170°)
  - CVT sea bajo (<5%)
  - Valores no salten drásticamente
✓ Resultado: PASS si todo es normal
```

### Test 2: Posición Incorrecta (DE FRENTE)

```
✓ Posiciónate DE FRENTE
✓ Haz 5 sentadillas
✓ Espera que:
  - Ángulos sean raros (>180° o negativos)
  - O valores no cambien
✓ Resultado: PASS si ves comportamiento anómalo
  (confirma que detección de posición funciona)
```

### Test 3: Profundidad

```
SENTADILLA PROFUNDA:
✓ θ_k ≤ 45° en punto bajo
✓ depthInsufficient = false
✓ Sin alerta de profundidad

SENTADILLA SUPERFICIAL:
✓ θ_k > 90° en punto bajo
✓ depthInsufficient = true
✓ Alerta de "Profundidad insuficiente"
```

### Test 4: Fatiga

```
✓ Haz 12 sentadillas sin descanso
✓ Rep 1-3: VL < 10% (estable)
✓ Rep 4-8: VL 10-20% (leve fatiga)
✓ Rep 9+: VL > 20% (ALERTA)
✓ Barra de Velocity Retention baja progresivamente
```

---

## 🐛 Troubleshooting

### Problema: Ángulos > 180° o Negativos

**Causa**: Probablemente estás de frente, no de lado

**Solución**: 
- Gira 90° para estar completamente de lado
- Posiciónate como si vieras tu reflejo en un espejo lateral

---

### Problema: CVT Siempre 0%

**Causa**: Necesitas 2+ reps para calcular CVT

**Solución**: Haz al menos 3 sentadillas completas

---

### Problema: Velocity Retention Congelado en 100%

**Causa**: Normal en rep 1, no hay comparación

**Solución**: Haz rep 2+, el porcentaje bajará

---

### Problema: App Cuelga

**Causa**: Carga computacional de MediaPipe

**Solución**:
- Reinicia la app
- Acércate más a la cámara
- Asegúrate de que hay buena iluminación

---

### Problema: Curl No Funciona

**Causa**: Posible que haya un bug

**Solución**:
- Reinstala: `./gradlew.bat installDebug`
- Limpia: `./gradlew.bat clean`
- Recompila

---

## 📊 Logs Útiles

Para ver logs en tiempo real:

```bash
# Terminal 1: Ver logs en vivo
adb logcat | grep "CameraActivity\|SquatBiomechanics\|CurlBiomechanics"

# Terminal 2: Ejecutar app
.\gradlew.bat installDebug
```

Busca mensajes como:
```
SquatBiomechanics: θ_k=62.5°
SquatBiomechanics: θ_h=85.0°
SquatBiomechanics: VL20=15.2%
SquatBiomechanics: CVT=4.8%
```

---

## 📱 Compatibilidad

| Aspecto | Requisito |
|---------|-----------|
| Android | 9+ (API 28+) |
| RAM | 2GB+ recomendado |
| Cámara | Cualquiera (mejor trasera) |
| MediaPipe | Versión integrada |

---

## 🎓 Documentación de Referencia

Si encuentras un problema o necesitas más detalle:

1. **Posición** → Ver `POSICION_VISUAL.md`
2. **Técnica** → Ver `README_SQUAT_IMPLEMENTATION.md`
3. **Testing** → Ver `TESTING_GUIDE.md`
4. **Referencia** → Ver `QUICK_REFERENCE.md`
5. **Todo** → Ver `INDICE_DOCUMENTACION.md`

---

## ✅ Checklist Final

Antes de considerar "listo para producción":

- [ ] Build compila sin errores
- [ ] Curl funciona como antes
- [ ] Squat detecta correctamente
- [ ] Tests unitarios pasan
- [ ] UI cambia dinámicamente
- [ ] Métricas en tiempo real funcionan
- [ ] Posición DE LADO se valida
- [ ] Documentación accesible

---

## 🚀 ¡Listo para Usar!

Una vez que hayas completado los pasos anteriores:

1. ✅ App compilada
2. ✅ Instalada en dispositivo
3. ✅ Tests pasando
4. ✅ Curl funcionando
5. ✅ Squat funcionando

**Puedes seleccionar "Barbell Squat", posicionarte DE LADO y comenzar a analizar sentadillas automáticamente.**

---

**Estado**: ✅ Listo para instalación y uso  
**Versión**: 1.0  
**Última actualización**: 2026-04-04

