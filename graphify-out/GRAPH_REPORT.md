# Graph Report - .  (2026-05-09)

## Corpus Check
- 66 files · ~495,432 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 508 nodes · 831 edges · 41 communities (24 shown, 17 thin omitted)
- Extraction: 88% EXTRACTED · 12% INFERRED · 0% AMBIGUOUS · INFERRED: 97 edges (avg confidence: 0.8)
- Token cost: 233,417 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Squat Biomechanics Engine|Squat Biomechanics Engine]]
- [[_COMMUNITY_Camera View & App Core|Camera View & App Core]]
- [[_COMMUNITY_Session Repository & Stats|Session Repository & Stats]]
- [[_COMMUNITY_Camera Activity Logic|Camera Activity Logic]]
- [[_COMMUNITY_UI Activities Layer|UI Activities Layer]]
- [[_COMMUNITY_Bench Press Biomechanics|Bench Press Biomechanics]]
- [[_COMMUNITY_Database Entities|Database Entities]]
- [[_COMMUNITY_Semantic Cross-References|Semantic Cross-References]]
- [[_COMMUNITY_Pose Detection Pipeline|Pose Detection Pipeline]]
- [[_COMMUNITY_Curl Biomechanics Engine|Curl Biomechanics Engine]]
- [[_COMMUNITY_Session State Holder|Session State Holder]]
- [[_COMMUNITY_User Preferences|User Preferences]]
- [[_COMMUNITY_Stats History UI|Stats History UI]]
- [[_COMMUNITY_Session DAO (Workout)|Session DAO (Workout)]]
- [[_COMMUNITY_Session Data Models|Session Data Models]]
- [[_COMMUNITY_Exercise Adapter & List|Exercise Adapter & List]]
- [[_COMMUNITY_Algorithms Dispatcher|Algorithms Dispatcher]]
- [[_COMMUNITY_Rep DAO|Rep DAO]]
- [[_COMMUNITY_Overlay View Rendering|Overlay View Rendering]]
- [[_COMMUNITY_Room Database Instance|Room Database Instance]]
- [[_COMMUNITY_Velocity Smoothing|Velocity Smoothing]]
- [[_COMMUNITY_One Euro Filter|One Euro Filter]]
- [[_COMMUNITY_Signal Smoothing Suite|Signal Smoothing Suite]]
- [[_COMMUNITY_Feedback Needs Improvement|Feedback: Needs Improvement]]
- [[_COMMUNITY_Landmark Smoother|Landmark Smoother]]
- [[_COMMUNITY_Feedback Keep It Up|Feedback: Keep It Up]]
- [[_COMMUNITY_App UI Screenshots|App UI Screenshots]]
- [[_COMMUNITY_Instrumented Tests|Instrumented Tests]]
- [[_COMMUNITY_Pose Data Manager|Pose Data Manager]]
- [[_COMMUNITY_Unit Tests|Unit Tests]]
- [[_COMMUNITY_Feedback On Track|Feedback: On Track]]
- [[_COMMUNITY_Build Configuration|Build Configuration]]
- [[_COMMUNITY_Session Aggregator|Session Aggregator]]
- [[_COMMUNITY_Summary UI|Summary UI]]
- [[_COMMUNITY_App Launcher Icons|App Launcher Icons]]
- [[_COMMUNITY_Select Exercise Screen|Select Exercise Screen]]
- [[_COMMUNITY_Home Screen|Home Screen]]

## God Nodes (most connected - your core abstractions)
1. `CameraActivity` - 33 edges
2. `SquatBiomechanicsAlgorithm` - 24 edges
3. `BenchPressBiomechanicsAlgorithm` - 23 edges
4. `VideoAnalysisActivity` - 20 edges
5. `StatsHistoryActivity` - 19 edges
6. `WorkoutSessionDao` - 16 edges
7. `SquatBiomechanicsAlgorithmTest` - 16 edges
8. `SummaryActivity` - 15 edges
9. `CurlBiomechanicsAlgorithm` - 14 edges
10. `get()` - 14 edges

## Surprising Connections (you probably didn't know these)
- `Settings Gradle (KTS)` --references--> `Root Build Gradle (KTS)`  [EXTRACTED]
  settings.gradle.kts → build.gradle.kts
- `App Build Gradle (KTS)` --references--> `Root Build Gradle (KTS)`  [EXTRACTED]
  app/build.gradle.kts → build.gradle.kts
- `CameraActivity` --calls--> `AppPreferences`  [INFERRED]
  app/src/main/java/com/rize/rizeandroid/CameraActivity.java → app/src/main/java/com/rize/rizeandroid/AppPreferences.java
- `VelocitySmoothing` --semantically_similar_to--> `OneEuroFilter`  [INFERRED] [semantically similar]
  app/src/main/kotlin/com/rize/rizeandroid/VelocitySmoothing.kt → app/src/main/kotlin/com/rize/rizeandroid/signal/OneEuroFilter.kt
- `HomepageActivity` --calls--> `AboutActivity`  [EXTRACTED]
  app/src/main/java/com/rize/rizeandroid/HomepageActivity.java → app/src/main/java/com/rize/rizeandroid/AboutActivity.java

## Hyperedges (group relationships)
- **Main Activity Navigation Flow** — homepageactivity, selectactivity, cameraactivity, summaryactivity, statshistoryactivity, sessionhistorydetailactivity [EXTRACTED 0.95]
- **Biomechanics Analysis Pipeline** — algorithms_kt, biomechanicsalgorithm, curlbiomechanicsalgorithm, benchpressbiomechanicsalgorithm, algorithmresult, cameraview_kt, cameraactivity [EXTRACTED 0.95]
- **Exercise Type Classification System** — exercisetype, exerciseadapter, selectactivity, cameraactivity, algorithms_kt [EXTRACTED 0.95]
- **Camera + MediaPipe + Overlay Rendering** — cameraviewmanager, cameraview_kt, overlayview_kt, skeletonoverlayview [EXTRACTED 0.95]
- **Room Database Layer** — rizedatabase_RizeDatabase, workoutsessiondao_WorkoutSessionDao, repdao_RepDao, workoutsession_WorkoutSession, squatsessiondetails_SquatSessionDetails, curlsessiondetails_CurlSessionDetails, benchsessiondetails_BenchSessionDetails, sessionrep_SessionRep, repsquatdetails_RepSquatDetails, repcurldetails_RepCurlDetails, repbenchdetails_RepBenchDetails [INFERRED 0.95]
- **Signal Smoothing Pipeline** — landmarksmoother_LandmarkSmoother, oneeuropfilter_OneEuroFilter, velocitysmoothing_VelocitySmoothing [INFERRED 0.85]
- **Session Persistence Flow** — sessionaggregator_PendingSessionBuilder, pendingsessiondata_PendingSessionData, pendingsessionholder_PendingSessionHolder, sessionrepository_SessionRepository [INFERRED 0.90]
- **Rize Core Exercise Features** — main_page_squat, main_page_benchpress, main_page_curl [EXTRACTED 1.00]

## Communities (41 total, 17 thin omitted)

### Community 0 - "Squat Biomechanics Engine"
Cohesion: 0.1
Nodes (7): AlgorithmResult, Landmark, LegLandmarks, RepPhase, SquatBiomechanicsAlgorithm, Vec3, SquatBiomechanicsAlgorithmTest

### Community 1 - "Camera View & App Core"
Cohesion: 0.09
Nodes (5): CameraViewManager, get(), RizeApplication, SkeletonOverlayView, VideoAnalysisActivity

### Community 2 - "Session Repository & Stats"
Cohesion: 0.1
Nodes (4): ExerciseStats, LocalSummary, SessionRepository, StatsHistoryActivity

### Community 4 - "UI Activities Layer"
Cohesion: 0.09
Nodes (8): AppCompatActivity, AboutActivity, run(), HomepageActivity, SessionHistoryDetailActivity, SummaryUiBinder, FrameData, View

### Community 5 - "Bench Press Biomechanics"
Cohesion: 0.12
Nodes (7): ArmLandmarks, BenchPressBiomechanicsAlgorithm, Landmark, ReadinessState, RepPhase, Side, Vec3

### Community 6 - "Database Entities"
Cohesion: 0.09
Nodes (11): PendingRep, PendingSessionData, PendingSessionBuilder, BenchSessionDetails, CurlSessionDetails, RepBenchDetails, RepCurlDetails, RepSquatDetails (+3 more)

### Community 7 - "Semantic Cross-References"
Cohesion: 0.16
Nodes (24): AboutActivity, AlgorithmResult (data class), Algorithms (Kotlin), AppPreferences, BenchPressBiomechanicsAlgorithm, BiomechanicsAlgorithm (interface), CameraActivity, CameraView (Kotlin) (+16 more)

### Community 8 - "Pose Detection Pipeline"
Cohesion: 0.12
Nodes (4): CameraView, LandmarkerListener, PoseLandmarkerHelper, ResultBundle

### Community 9 - "Curl Biomechanics Engine"
Cohesion: 0.17
Nodes (8): ArmLandmarks, ArmSample, ArmSide, CurlBiomechanicsAlgorithm, ErrorResult, FatigueResult, Landmark, Vec3

### Community 12 - "Stats History UI"
Cohesion: 0.12
Nodes (5): ExerciseStatsAdapter, Holder, OnSessionClickListener, SessionAdapter, SessionCardModel

### Community 14 - "Session Data Models"
Cohesion: 0.37
Nodes (17): BenchSessionDetails, CurlSessionDetails, PendingRep, PendingSessionData, PendingSessionHolder, RepBenchDetails, RepCurlDetails, RepDao (+9 more)

### Community 15 - "Exercise Adapter & List"
Cohesion: 0.15
Nodes (4): Exercise, ExerciseAdapter, OnExerciseClickListener, ViewHolder

### Community 16 - "Algorithms Dispatcher"
Cohesion: 0.23
Nodes (5): Algorithms, BiomechanicsAlgorithm, ErrorLevel, SquatDepthCategory, SquatTrunkCategory

### Community 22 - "Signal Smoothing Suite"
Cohesion: 0.4
Nodes (5): LandmarkSmoother, OneEuroFilter, SquatBiomechanicsAlgorithm, SquatBiomechanicsAlgorithmTest, VelocitySmoothing

### Community 23 - "Feedback: Needs Improvement"
Cohesion: 0.4
Nodes (5): A Mejorar Needs Improvement State, A Mejorar Drawable Resource, A Mejorar Source Image, Performance Feedback UI Component, Rize Brand Identity

### Community 25 - "Feedback: Keep It Up"
Cohesion: 0.5
Nodes (4): Motivational UI Feedback Element, Positive Feedback Keep It Up Badge, Sigue Asi Drawable Resource, SIGUE_ASI Source Image

### Community 26 - "App UI Screenshots"
Cohesion: 0.5
Nodes (4): Bench Press Exercise Feature, Curl Exercise Feature, Squat Exercise Feature, Main Page UI Screenshot

### Community 30 - "Feedback: On Track"
Cohesion: 0.67
Nodes (3): App Build Gradle (KTS), Root Build Gradle (KTS), Settings Gradle (KTS)

### Community 31 - "Build Configuration"
Cohesion: 0.67
Nodes (3): On the Right Track Progress Feedback State, Por Buen Camino Drawable Resource, Por Buen Camino Source Image

### Community 32 - "Session Aggregator"
Cohesion: 0.67
Nodes (3): Rize Round Launcher Icon, Rize Square Launcher Icon, Orange Neon Trend Logo Concept

## Knowledge Gaps
- **35 isolated node(s):** `ErrorLevel`, `SquatDepthCategory`, `SquatTrunkCategory`, `RepPhase`, `ReadinessState` (+30 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **17 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `run()` connect `UI Activities Layer` to `Squat Biomechanics Engine`, `Curl Biomechanics Engine`, `Session State Holder`?**
  _High betweenness centrality (0.167) - this node is a cross-community bridge._
- **Why does `CameraActivity` connect `Camera Activity Logic` to `UI Activities Layer`?**
  _High betweenness centrality (0.130) - this node is a cross-community bridge._
- **Why does `AlgorithmResult` connect `Squat Biomechanics Engine` to `Algorithms Dispatcher`, `Curl Biomechanics Engine`, `Bench Press Biomechanics`?**
  _High betweenness centrality (0.122) - this node is a cross-community bridge._
- **Are the 6 inferred relationships involving `SquatBiomechanicsAlgorithm` (e.g. with `.detectsFatigueWhenConcentricVelocityDropsMoreThan20Percent()` and `.flagsDepthAndTrunkRiskOnShallowAndLeaningRep()`) actually correct?**
  _`SquatBiomechanicsAlgorithm` has 6 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ErrorLevel`, `SquatDepthCategory`, `SquatTrunkCategory` to the rest of the system?**
  _35 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Squat Biomechanics Engine` be split into smaller, more focused modules?**
  _Cohesion score 0.1 - nodes in this community are weakly interconnected._
- **Should `Camera View & App Core` be split into smaller, more focused modules?**
  _Cohesion score 0.09 - nodes in this community are weakly interconnected._