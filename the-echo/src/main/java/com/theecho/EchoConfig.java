package com.theecho;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Se guarda en config/the_echo.json */
public final class EchoConfig {
    // Días (del mundo) en los que se desbloquea cada fase
    public int startDay = 3;       // clon silencioso + paranoia de dread
    public int villagerDay = 4;    // aldeanos/animales que miran fijamente
    public int audioDay = 4;       // sonidos falsos
    public int mimicDay = 5;       // imita tus acciones (talar, dormir)
    public int interfaceDay = 6;   // carteles y antorchas
    public int hijackDay = 9;      // el Eco puede llevarte al reino espejo

    public double dreadPerMinute = 3.0;       // "miedo" acumulado por minuto jugado
    public double hijackDread = 100.0;        // umbral para el secuestro
    public double offeringDreadReduction = 35.0;
    public int offeringBanishDays = 1;        // días de calma por ofrenda
    public int maxBanishDays = 5;
    public int mirrorRadius = 24;             // radio de protección de un espejo
    public int realmRadius = 12;              // radio de la réplica oscura (más grande = más lag al generarse)
    public int mazeSize = 7;                  // celdas por lado del laberinto (5 a 11)
    public boolean enableRealm = true;

    public static EchoConfig INSTANCE = new EchoConfig();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("the_echo.json");
        try {
            if (Files.exists(path)) {
                try (Reader r = Files.newBufferedReader(path)) {
                    EchoConfig c = GSON.fromJson(r, EchoConfig.class);
                    if (c != null) INSTANCE = c;
                }
            }
            try (Writer w = Files.newBufferedWriter(path)) {
                GSON.toJson(INSTANCE, w);
            }
        } catch (IOException | RuntimeException e) {
            TheEcho.LOGGER.error("No se pudo leer/escribir la config del Eco", e);
        }
    }
}
