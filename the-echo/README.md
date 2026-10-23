# El Eco / The Echo — Fabric 1.21.1

## Compilar (la vía más fácil, sin instalar nada)
Sube esta carpeta a un repositorio de GitHub: el workflow `.github/workflows/build.yml` compila solo.
Descarga el jar desde la pestaña **Actions → build → Artifacts** (usa el que NO termina en `-sources`).

## Compilar en tu PC
Requisitos: JDK 21. Copia `gradlew`, `gradlew.bat` y la carpeta `gradle/` del
[fabric-example-mod](https://github.com/FabricMC/fabric-example-mod) (rama 1.21) o ten Gradle 8.8+ instalado.

    ./gradlew build        # el .jar queda en build/libs/the-echo-1.0.0.jar

Mételo en `mods/` junto a Fabric API (0.105.0+1.21.1 o superior para 1.21.1). Para probar: `./gradlew runClient`.

## Config: `config/the_echo.json`
Días de desbloqueo de cada fase, velocidad del "miedo", radio del espejo, radio de la réplica, etc.

## Comandos de prueba (op nivel 2)
`/echo status` · `/echo spawn` · `/echo dread <n>` · `/echo hijack` · `/echo escape` · `/echo reset`

## Cómo juega
- **Día 3+**: clones a lo lejos (se desvanecen si te acercas o los miras mucho).
- **Día 4+**: aldeanos/animales te miran fijo; hablarles reproduce tus sonidos recientes, deformados.
  Sonidos falsos (pasos, cofres, puertas, tu propio daño).
- **Día 5+**: si talas un árbol, un clon tala otro a lo lejos. Al dormir, huellas (caminos de tierra) y puertas abiertas.
- **Día 6+**: carteles que cambian una letra; antorchas con llama azul.
- **Calmarlo**: craftea el *Espejo del Eco* (vidrio ×5, amatista, hierro ×3) y colócalo (protege un radio).
  Clic derecho con un objeto que tuviste el **primer día** = ofrenda (1 por tipo; da días de calma).
- **Clímax (día 9+, miedo ≥ 100)**: al despertar apareces en una réplica oscura de tu base. Encuentra
  **El Original** (bloque que brilla tenue y suelta partículas) y púlsalo para volver.

## Cambios de la v1.0.1
- Skin slim (Alex) ahora usa el modelo de brazos finos.
- El espejo ya no se ve con fondo negro (capa translúcida).
- Menos lag al generar el reino (solo escribe bloques que cambian; radio por defecto 12).
- Mobs congelados se liberan solos si cambias de dimensión o te desconectas.
- Nuevas imitaciones: abrir cofres, barriles y puertas también se graban y se reproducen.
- Si la dimensión no está cargada, avisa en el chat en vez de fallar en silencio.

## Cambios de la v1.1.0 (terror++)
- **Reino espejo = laberinto** de deepslate que se genera distinto cada vez que entras (`mazeSize` en la config, 5 a 11).
- **Ecos cazadores**: solo se mueven cuando no los miras; si te tocan, te devuelven al inicio. Aparecen más con el tiempo (hasta 5).
- **Jumpscares** en los callejones sin salida (pantallazo + grito + un Eco frente a ti).
- **Efectos en primera persona** según tu miedo: viñeta que se cierra, pulso rojo, latido, temblor de cámara y ojos en la oscuridad.
- **Alucinaciones**: Ecos de reojo que desaparecen al mirarlos, mensajes falsos en el chat con tu nombre, sonidos de creeper/gritos/respiración.
- Más apariciones del Eco y el miedo sube más rápido (también en la oscuridad).
