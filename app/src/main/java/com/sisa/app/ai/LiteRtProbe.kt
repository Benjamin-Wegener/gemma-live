package com.sisa.app.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SessionConfig

// TEMPORÄR: nur Metadaten-Kompatibilitätstest (Kotlin 1.9.20 vs. LiteRT-LM 0.17.1)
internal object LiteRtProbe {
    fun probe(modelPath: String): String {
        val cfg = EngineConfig(modelPath = modelPath, backend = Backend.GPU())
        val engine = Engine(cfg)
        engine.initialize()
        val session = engine.createSession(SessionConfig())
        session.close()
        engine.close()
        return "ok"
    }
}
