# 🚀 Quick Reference - Sentadilla en RIZE

## ⚡ Respuesta Rápida

### P: ¿De frente o de lado?
**R: DE LADO** ← (vista lateral, 90° a cámara)

---

## 🎯 Setup en 30 segundos

```
1. Abre app → Selecciona "Barbell Squat"
2. Posición: DE LADO (perfil derecho o izquierdo)
3. Distancia: 1.5-2 metros
4. Haz sentadilla
5. UI muestra: Knee Angle, Technical CVT, Velocity Retention
```

---

## 📊 Métricas Principales

| Métrica | Símbolo | Normal | Alerta |
|---------|---------|--------|--------|
| Knee Angle | θ_k | 40-100° | >100° (superficial) |
| Hip Angle | θ_h | 80-120° | <80° (tronco riesgo) |
| CVT | - | <5% | >10% (inestable) |
| Velocity Loss | VL20 | <10% | ≥20% (fatiga) |

---

## ✅ Checklist Posición

- [ ] De lado (perfil lateral)
- [ ] 90° perpendicular a cámara
- [ ] 1.5-2 metros distancia
- [ ] Cuerpo completo visible
- [ ] Buena iluminación

---

## 🔴 Errores Técnicos Detectados

```
🔴 PROFUNDIDAD INSUFICIENTE
   → θ_k > 45° (no baja lo suficiente)
   → Sube "depthInsufficient: true"

🔴 RIESGO DE TRONCO
   → θ_h < 80° (demasiada inclinación)
   → Sube "trunkLeanRisk: true"

🔴 FATIGA TÉCNICA
   → VL20 ≥ 20% (velocidad cae mucho)
   → Sube "fatigueDetected: true"

🟠 INESTABILIDAD
   → CVT > 10% (profundidad muy variable)
   → Color naranja/rojo en Technical CVT
```

---

## 📁 Archivos Clave

```
Código:
  app/src/main/kotlin/.../SquatBiomechanicsAlgorithm.kt
  app/src/test/java/.../SquatBiomechanicsAlgorithmTest.kt

UI:
  app/src/main/java/.../CameraActivity.java
  app/src/main/res/layout/activity_camera.xml
  
Docs:
  INDICE_DOCUMENTACION.md ← EMPIEZA AQUÍ
  POSICION_VISUAL.md
  README_SQUAT_IMPLEMENTATION.md
  TESTING_GUIDE.md
```

---

## 🧪 Comprobar Que Funciona

```bash
# Compilar
./gradlew.bat clean build

# Tests
./gradlew.bat testDebugUnitTest

# Instalar en dispositivo
./gradlew.bat installDebug
```

---

## 💡 Tips

- **Mismo lado**: Usa siempre el mismo lado (derecho o izquierdo) en una sesión
- **Iluminación**: Mejor luz frontal o lateral
- **Ropa**: Preferir ajustada (mejor detección articular)
- **Distancia**: Fija (no te muevas hacia/lejos cámara)

---

## ❓ FAQ

| Q | A |
|---|---|
| ¿De frente o lado? | **LADO** |
| ¿Qué es θ_k? | Ángulo rodilla (cadera-rodilla-tobillo) |
| ¿Qué es CVT? | Consistencia técnica (variabilidad entre reps) |
| ¿Qué es VL20? | Pérdida velocidad ≥20% = fatiga |
| ¿Curl funciona? | Sí, intacto |
| ¿Cómo cambio de lado? | Entre series, no en medio |
| ¿Valores > 180°? | Probablemente de frente, gira a lado |
| ¿CVT siempre 0%? | Normal, necesita 2+ reps para calcular |

---

## 📞 Consulta Completa

Ir a: `INDICE_DOCUMENTACION.md` para todos los links y referencias

---

**Versión**: 1.0  
**Estado**: ✅ Production Ready  
**Última actualización**: 2026-04-04

