# 📚 Índice de Documentación - Implementación de Sentadilla en RIZE

Bienvenido. Aquí encontrarás toda la información sobre la implementación de análisis de sentadilla.

---

## 🎯 ¿Dónde Empezar?

### Si eres **Usuario Final** 👤

1. **Primero**: Lee → [`POSICION_VISUAL.md`](POSICION_VISUAL.md)
   - Entiende cómo posicionarte (DE LADO, no de frente)
   - Diagramas visuales y ejemplos

2. **Luego**: Lee → [`POSICION_SENTADILLA.md`](POSICION_SENTADILLA.md)
   - Guía completa con checklist
   - Solución de problemas

3. **Durante uso**: Consulta → [`TESTING_GUIDE.md`](TESTING_GUIDE.md) (sección "Métricas Esperadas")
   - Qué valores son normales
   - Qué significan las alertas

---

### Si eres **Desarrollador** 👨‍💻

1. **Primero**: Lee → [`README_SQUAT_IMPLEMENTATION.md`](README_SQUAT_IMPLEMENTATION.md)
   - Visión general de la implementación
   - Parámetros biomecánicos
   - Flujo de datos

2. **Luego**: Revisa el código:
   - [`SquatBiomechanicsAlgorithm.kt`](app/src/main/kotlin/com/rize/rizeandroid/SquatBiomechanicsAlgorithm.kt) → Lógica principal
   - [`Algorithms.kt`](app/src/main/kotlin/com/rize/rizeandroid/Algorithms.kt) → Enrutamiento
   - [`CameraActivity.java`](app/src/main/java/com/rize/rizeandroid/CameraActivity.java) → UI/Integration

3. **Testing**: Lee → [`TESTING_GUIDE.md`](TESTING_GUIDE.md)
   - Pasos para validar
   - Criterios de aceptación
   - Troubleshooting

---

### Si necesitas **Implementar Mejoras** 🔧

1. Consulta parámetros ajustables en [`README_SQUAT_IMPLEMENTATION.md`](README_SQUAT_IMPLEMENTATION.md) (sección "Parámetros Ajustables")
2. Modifica constantes en [`SquatBiomechanicsAlgorithm.kt`](app/src/main/kotlin/com/rize/rizeandroid/SquatBiomechanicsAlgorithm.kt)
3. Agrega tests en [`SquatBiomechanicsAlgorithmTest.kt`](app/src/test/java/com/rize/rizeandroid/SquatBiomechanicsAlgorithmTest.kt)
4. Ejecuta validación

---

## 📄 Documentos Disponibles

### 1. **POSICION_VISUAL.md** 🎯
**Audiencia**: Usuarios finales + Diseñadores
**Contenido**:
- Diagramas visuales de posición correcta
- Ejemplos de posición incorrecta
- Checklist antes de grabar
- Tips pro
- Señales de alerta

**Lee si**: Quieres entender dónde pararse

---

### 2. **POSICION_SENTADILLA.md** 📍
**Audiencia**: Usuarios + Product Managers
**Contenido**:
- Explicación: ¿Por qué de lado?
- Setup correcto (1.5-2 metros, luz, etc.)
- Recomendaciones detalladas
- Ejemplo en video
- Resumen comparativo

**Lee si**: Necesitas guía completa de posicionamiento

---

### 3. **README_SQUAT_IMPLEMENTATION.md** 📖
**Audiencia**: Desarrolladores + Product Owners
**Contenido**:
- Especificación de parámetros biomecánicos (θ_k, θ_h, ω_k, VL20, CVT)
- Fórmulas matemáticas
- Archivos modificados/creados
- Flujo de datos
- Algoritmo de detección de reps (máquina de estados)
- UI actualizada
- Pruebas unitarias
- Métricas esperadas
- Limitaciones
- Próximos pasos

**Lee si**: Eres developer o necesitas entender la técnica

---

### 4. **TESTING_GUIDE.md** 🧪
**Audiencia**: QA + Desarrolladores + Beta Testers
**Contenido**:
- 9 pasos de validación (curl, squat, posición, profundidad, fatiga, tronco, estabilidad, etc.)
- Métricas esperadas por rango
- Checklist final de QA
- Solución de problemas comunes
- Plantilla de reporte

**Lee si**: Vas a testar la implementación

---

### 5. **RESPUESTA_POSICION_SENTADILLA.md** (este índice)
**Audiencia**: Todos
**Contenido**:
- Respuesta directa: ¿De frente o de lado? → **DE LADO**
- Comparativa rápida
- Posicionamiento en diagrama
- Ejemplos visuales
- TL;DR

**Lee si**: Tienes prisa y necesitas respuesta rápida

---

## 🔗 Enlaces Directos

### Código Principal
- [SquatBiomechanicsAlgorithm.kt](app/src/main/kotlin/com/rize/rizeandroid/SquatBiomechanicsAlgorithm.kt) - Algoritmo
- [Algorithms.kt](app/src/main/kotlin/com/rize/rizeandroid/Algorithms.kt) - Enrutamiento
- [CameraActivity.java](app/src/main/java/com/rize/rizeandroid/CameraActivity.java) - UI/Integration
- [SquatBiomechanicsAlgorithmTest.kt](app/src/test/java/com/rize/rizeandroid/SquatBiomechanicsAlgorithmTest.kt) - Tests

### Layout & Strings
- [activity_camera.xml](app/src/main/res/layout/activity_camera.xml) - Layout UI
- [strings.xml](app/src/main/res/values/strings.xml) - Textos

---

## ❓ FAQ Rápido

### P: ¿De frente o de lado?
**R**: DE LADO (vista lateral/sagital). Ver [`POSICION_VISUAL.md`](POSICION_VISUAL.md)

### P: ¿Cuáles son las métricas principales?
**R**: θ_k (rodilla), θ_h (cadera), ω_k (velocidad), VL20 (fatiga), CVT (estabilidad). Ver [`README_SQUAT_IMPLEMENTATION.md`](README_SQUAT_IMPLEMENTATION.md)

### P: ¿Qué significa CVT > 10%?
**R**: Inestabilidad técnica. La profundidad varía mucho entre reps. Ver [`README_SQUAT_IMPLEMENTATION.md`](README_SQUAT_IMPLEMENTATION.md)

### P: ¿Qué significa VL20?
**R**: Pérdida de velocidad ≥ 20% → Fatiga técnica significativa. Ver [`README_SQUAT_IMPLEMENTATION.md`](README_SQUAT_IMPLEMENTATION.md)

### P: ¿Curl sigue funcionando?
**R**: Sí, intacto. Las métricas se mantienen igual que antes. Ver [`TESTING_GUIDE.md`](TESTING_GUIDE.md) paso 1

### P: ¿Cómo testar?
**R**: Sigue los 9 pasos de [`TESTING_GUIDE.md`](TESTING_GUIDE.md)

### P: ¿Dónde está el código?
**R**: `app/src/main/kotlin/com/rize/rizeandroid/SquatBiomechanicsAlgorithm.kt`

---

## 🚀 Flujo Recomendado de Lectura

```
Primero vez
└─→ POSICION_VISUAL.md
    └─→ POSICION_SENTADILLA.md
        └─→ Prueba en app

Developer (primero vez)
└─→ README_SQUAT_IMPLEMENTATION.md
    └─→ Ver código (SquatBiomechanicsAlgorithm.kt)
        └─→ TESTING_GUIDE.md
            └─→ Pruebas unitarias + manuales

QA / Beta Tester
└─→ TESTING_GUIDE.md
    └─→ Ejecutar 9 pasos
        └─→ Reportar resultados

Necesito ajustar parámetros
└─→ README_SQUAT_IMPLEMENTATION.md (secc. "Parámetros Ajustables")
    └─→ Editar SquatBiomechanicsAlgorithm.kt
        └─→ Agregar tests en SquatBiomechanicsAlgorithmTest.kt
            └─→ Validar con TESTING_GUIDE.md
```

---

## 📊 Resumen de Cambios

| Tipo | Archivo | Cambio |
|------|---------|--------|
| ✨ Nuevo | `SquatBiomechanicsAlgorithm.kt` | Algoritmo principal |
| ✨ Nuevo | `SquatBiomechanicsAlgorithmTest.kt` | Tests unitarios |
| 🔧 Modificado | `Algorithms.kt` | Enrutamiento squat |
| 🔧 Modificado | `CameraActivity.java` | UI dinámica |
| 🔧 Modificado | `activity_camera.xml` | IDs dinámicos |
| 🔧 Modificado | `strings.xml` | Textos de squat |
| 📚 Doc | Varios `.md` | Documentación |

---

## ✅ Estado de Implementación

- [x] Algoritmo implementado y testado
- [x] Integración en CameraActivity
- [x] UI actualizada dinámicamente
- [x] Curl intacto (sin cambios)
- [x] Build exitoso
- [x] Tests unitarios pasan
- [x] Documentación completa

**Estado Global**: ✅ **LISTO PARA PRUEBAS**

---

## 🎓 Recursos Externos

- [MediaPipe Pose Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker)
- Documento interno: "Medición de variables cinemáticas" (§1-§9)
- Paper: Serbest, Ş., et al. (2022). "A Biomechanical Analysis of Dumbbell Curl..."

---

## 📞 Contacto y Preguntas

Si tienes preguntas sobre:

- **Posición**: Consulta `POSICION_VISUAL.md` o `POSICION_SENTADILLA.md`
- **Técnica**: Consulta `README_SQUAT_IMPLEMENTATION.md`
- **Testing**: Consulta `TESTING_GUIDE.md`
- **Código**: Revisa comentarios en `SquatBiomechanicsAlgorithm.kt`

---

**Última actualización**: 2026-04-04  
**Versión**: 1.0  
**Estado**: ✅ Producción

🎉 ¡Listo para usar! Selecciona "Barbell Squat" en la app y posiciónate **DE LADO** para comenzar.

