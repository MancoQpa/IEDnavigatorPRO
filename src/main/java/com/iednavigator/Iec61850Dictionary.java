package com.iednavigator;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diccionario educativo IEC 61850.
 * Cubre: Nodos Lógicos (7-4), CDC (7-3), FC (7-2), DOs y DAs comunes.
 * Uso: Iec61850Dictionary.showInfoDialog(parent, nodeName)
 */
public class Iec61850Dictionary {

    // ─────────────────────────────────────────────────────────────────────────
    //  Tipos de entrada
    // ─────────────────────────────────────────────────────────────────────────
    enum EntryType {
        LOGICAL_NODE  ("dict.type.ln",  new Color(0x1565C0), "LN"),
        CDC           ("dict.type.cdc", new Color(0x2E7D32), "CDC"),
        FC            ("dict.type.fc",  new Color(0x6A1B9A), "FC"),
        DATA_OBJECT   ("dict.type.do",  new Color(0xE65100), "DO"),
        DATA_ATTRIBUTE("dict.type.da",  new Color(0x37474F), "DA"),
        LOGICAL_DEVICE("dict.type.ld",  new Color(0x00695C), "LD"),
        SPECIAL       ("dict.type.special", new Color(0x78909C), "??");

        final String labelKey;
        final Color  color;
        final String badge;
        EntryType(String labelKey, Color color, String badge) {
            this.labelKey = labelKey; this.color = color; this.badge = badge;
        }
        /** Etiqueta traducida — se resuelve al momento de mostrarla, no al cargar la clase. */
        String label() { return I18n.t(labelKey); }
    }

    static class Entry {
        final EntryType type;
        final String    fullNameES;
        final String    fullNameEN;
        final String    description;
        final String    standard;
        final String    example;   // puede ser null

        // Traducción china/portuguesa de la descripción/ejemplo (opcional, se completa aparte
        // para no tocar las entradas existentes). Términos clave IEC 61850 (LN/CDC/FC, nombres
        // de nodo) se dejan sin traducir dentro del texto traducido.
        String descriptionZh;
        String exampleZh;
        String descriptionPt;
        String examplePt;
        String descriptionEn;
        String exampleEn;

        Entry(EntryType type, String fullNameES, String fullNameEN,
              String description, String standard, String example) {
            this.type = type; this.fullNameES = fullNameES; this.fullNameEN = fullNameEN;
            this.description = description; this.standard = standard; this.example = example;
        }
        Entry(EntryType type, String fullNameES, String fullNameEN,
              String description, String standard) {
            this(type, fullNameES, fullNameEN, description, standard, null);
        }

        /** Descripción a mostrar según el idioma activo (cae al español si no hay traducción). */
        String localizedDescription() {
            String tag = I18n.currentTag();
            if ("zh".equals(tag) && descriptionZh != null) return descriptionZh;
            if ("pt".equals(tag) && descriptionPt != null) return descriptionPt;
            if ("en".equals(tag) && descriptionEn != null) return descriptionEn;
            return description;
        }
        /** Ejemplo a mostrar según el idioma activo (cae al español si no hay traducción). */
        String localizedExample() {
            String tag = I18n.currentTag();
            if ("zh".equals(tag) && exampleZh != null) return exampleZh;
            if ("pt".equals(tag) && examplePt != null) return examplePt;
            if ("en".equals(tag) && exampleEn != null) return exampleEn;
            return example;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Diccionario principal
    // ─────────────────────────────────────────────────────────────────────────
    private static final Map<String, Entry> DICT = new LinkedHashMap<>();

    static {
        // ── Nodos del sistema ────────────────────────────────────────────────
        ln("LLN0",  "Nodo Lógico Cero",            "Logical Node Zero",
           "Nodo raíz del Dispositivo Lógico. Contiene los bloques de control de reportes " +
           "(RCB), bloques de control GOOSE (GoCB), datasets y la configuración general del LD.",
           "IEC 61850-7-4 §5.2",
           "LLN0.Mod (modo de operación), LLN0.NamPlt (placa de datos)");

        ln("LPHD",  "Dispositivo Físico",            "Physical Device",
           "Representa el dispositivo físico real (IED). Contiene información de inventario " +
           "(fabricante, modelo, versión de firmware), estado de salud y comunicación.",
           "IEC 61850-7-4 §5.3",
           "LPHD1.NamPlt (fabricante/modelo), LPHD1.PhyHealth (estado del hardware)");

        // ── Control (C) ──────────────────────────────────────────────────────
        ln("CSWI",  "Controlador de Interruptor",   "Switch Controller",
           "Controla la operación (apertura/cierre) de un interruptor o seccionador. " +
           "Recibe comandos SBO (Select-Before-Operate) o directos e interactúa con CILO " +
           "para verificar que el enclavamiento permita la maniobra.",
           "IEC 61850-7-4 §6.2.3",
           "CSWI1.Pos (posición), CSWI1.Auto (modo automático)");

        ln("CILO",  "Enclavamiento",                "Interlocking",
           "Implementa la lógica de enclavamiento. Evalúa las condiciones de seguridad " +
           "(posición de otros interruptores, presencia de tensión, etc.) antes de autorizar " +
           "una maniobra. Bloquea o habilita el comando sobre CSWI.",
           "IEC 61850-7-4 §6.2.2",
           "CILO1.EnaOpn (habilitar apertura), CILO1.EnaCls (habilitar cierre)");

        ln("CALH",  "Gestión de Alarmas",           "Alarm Handling",
           "Procesa y prioriza alarmas de proceso y sistema. Puede agrupar, filtrar y " +
           "escalar alarmas, y lleva registro histórico de eventos.",
           "IEC 61850-7-4 §6.2.1");

        ln("CCGR",  "Control de Grupo Refrigeración","Cooling Group Control",
           "Controla los sistemas de refrigeración de transformadores u otros equipos " +
           "(bombas, ventiladores). Activa etapas según temperatura.",
           "IEC 61850-7-4 §6.2.4");

        ln("CPOW",  "Detección de Oscilaciones",    "Power Swing Detection / Control",
           "Detecta oscilaciones de potencia inter-área y puede bloquear protecciones de " +
           "distancia durante la oscilación para evitar disparos incorrectos.",
           "IEC 61850-7-4 §6.2.5");

        ln("CSYN",  "Sincronizador",                "Synchronizer",
           "Verifica que las condiciones de sincronismo (diferencia de tensión, frecuencia " +
           "y ángulo de fase) sean aceptables antes de cerrar un interruptor y conectar " +
           "dos sistemas eléctricos.",
           "IEC 61850-7-4 §6.2.6");

        // ── Switchgear (X) ───────────────────────────────────────────────────
        ln("XCBR",  "Interruptor de Potencia",      "Circuit Breaker",
           "Representa el interruptor de potencia físico. Reporta su posición real " +
           "(Pos: intermedio/abierto/cerrado/defecto), contadores de operaciones, " +
           "tiempo de apertura/cierre y estado del accionamiento.",
           "IEC 61850-7-4 §8.1",
           "XCBR1.Pos [ST] = on/off, XCBR1.OpCnt (contador de operaciones)");

        ln("XSWI",  "Seccionador / Cuchilla",       "Switch (Disconnector / Earth Switch)",
           "Representa un aparato de corte sin capacidad de interrupción de corriente " +
           "de falta: seccionador, cuchilla de puesta a tierra, by-pass, etc. " +
           "Similar a XCBR pero sin arco de interrupción.",
           "IEC 61850-7-4 §8.2",
           "XSWI1.Pos [ST], XSWI1.Loc (operación local)");

        // ── Protección (P) ───────────────────────────────────────────────────
        ln("PDIS",  "Protección de Distancia",      "Distance Protection",
           "Mide la impedancia vista desde el relé y actúa si cae dentro de una zona " +
           "predefinida (Z1, Z2, Z3…). Principal protección de líneas de transmisión.",
           "IEC 61850-7-4 §7.3.2",
           "PDIS1.Op (operación), PDIS1.Str (arranque), PDIS1.Z1 (zona 1)");

        ln("PDIF",  "Protección Diferencial",       "Differential Protection",
           "Compara las corrientes de entrada y salida de un elemento protegido " +
           "(transformador, generador, barra, cable). Actúa cuando la diferencia " +
           "supera el umbral, indicando falta interna.",
           "IEC 61850-7-4 §7.3.1",
           "PDIF1.Op, PDIF1.Str, PDIF1.RsDif (corriente diferencial de arranque)");

        ln("PTOC",  "Sobreintensidad Temporizada",  "Time Overcurrent Protection",
           "Protección de sobreintensidad con característica de tiempo inverso o definido. " +
           "Dispara cuando la corriente supera el ajuste de arranque (Str) durante " +
           "el tiempo calculado por la curva.",
           "IEC 61850-7-4 §7.3.28",
           "PTOC1.Str (arranque), PTOC1.Op (operación/disparo), PTOC1.DirMod (direccional)");

        ln("PIOC",  "Sobreintensidad Instantánea",  "Instantaneous Overcurrent",
           "Protección de sobreintensidad de actuación instantánea (sin retardo intencional). " +
           "Cubre faltas de alta corriente, normalmente el 50 de los relés electromagnéticos.",
           "IEC 61850-7-4 §7.3.13",
           "PIOC1.Op (operación), PIOC1.Str (arranque)");

        ln("PTOV",  "Sobretensión Temporizada",     "Time Overvoltage Protection",
           "Actúa cuando la tensión supera el nivel de ajuste durante el tiempo " +
           "definido por su curva o retardo fijo. Protege equipos sensibles a sobretensión.",
           "IEC 61850-7-4 §7.3.30");

        ln("PTUV",  "Subtensión Temporizada",       "Time Undervoltage Protection",
           "Actúa cuando la tensión cae por debajo del nivel de ajuste. " +
           "Detecta pérdida de tensión, hundimientos prolongados o fallo de la red.",
           "IEC 61850-7-4 §7.3.31");

        ln("PTRC",  "Condicionamiento de Disparo",  "Protection Trip Conditioning",
           "Centraliza y condiciona los disparos de múltiples funciones de protección " +
           "antes de enviar la orden al disyuntor. Permite lógicas AND/OR, bloqueos y " +
           "secuenciación de disparos.",
           "IEC 61850-7-4 §7.3.27",
           "PTRC1.Tr (disparo general), PTRC1.Op1...Op3 (disparos individuales)");

        ln("PSCH",  "Esquema de Protección",        "Protection Scheme",
           "Coordina múltiples funciones de protección como esquema completo " +
           "(diferencial de línea, transferencia de disparo, aceleración de zona, etc.).",
           "IEC 61850-7-4 §7.3.22");

        ln("PHAR",  "Protección Armónica",          "Harmonic Restraint",
           "Bloquea o restringe la protección diferencial de transformador durante " +
           "la energización (inrush) detectando la alta componente de 2.° armónico.",
           "IEC 61850-7-4 §7.3.8");

        ln("PHIZ",  "Detección de Falta Imp. Alta", "High Impedance Fault Detection",
           "Detecta faltas de alta impedancia a tierra (árbol caído, pavimento húmedo) " +
           "que no producen suficiente corriente para los relés convencionales.",
           "IEC 61850-7-4 §7.3.9");

        ln("PTEF",  "Falta a Tierra Temporizada",   "Time Earth Fault",
           "Protección de falta a tierra con característica temporizada. " +
           "Detecta corrientes de secuencia cero o residuales que indican falta a tierra.",
           "IEC 61850-7-4 §7.3.26");

        ln("PSDE",  "Falta Tierra Dir. Sensible",   "Sensitive Directional Earth Fault",
           "Protección de falta a tierra sensible y direccional para redes con neutro " +
           "aislado o compensado (Petersen). Detecta corrientes de falta muy pequeñas.",
           "IEC 61850-7-4 §7.3.23");

        ln("PRTR",  "Falta Tierra Restringida",     "Restricted Earth Fault Protection",
           "Protección diferencial de falta a tierra para el devanado de un transformador. " +
           "Compara la corriente del neutro con la suma de corrientes de fase.",
           "IEC 61850-7-4 §7.3.19");

        ln("PDOP",  "Potencia Direccional",         "Directional Overpower Protection",
           "Actúa cuando el flujo de potencia activa en una dirección supera el ajuste. " +
           "Útil para generadores e interconexiones.",
           "IEC 61850-7-4 §7.3.4");

        ln("PUPF",  "Protección Factor de Potencia","Under Power Factor Protection",
           "Actúa cuando el factor de potencia cae por debajo del ajuste. " +
           "Protege generadores de operación en zona de absorción reactiva excesiva.",
           "IEC 61850-7-4 §7.3.32");

        ln("PVOC",  "SobreInt. Ctrl. Tensión",      "Voltage Controlled Time Overcurrent",
           "Sobreintensidad temporizada cuyo ajuste de arranque varía automáticamente " +
           "en función de la tensión, útil para generadores con reducción de tensión en falta.",
           "IEC 61850-7-4 §7.3.34");

        ln("PZSU",  "Subvelocidad/Rotor Parado",    "Zero Speed / Underspeed",
           "Detecta que una máquina giratoria está por debajo de la velocidad nominal " +
           "o completamente parada, protegiendo contra arranque incompleto o pérdida de paso.",
           "IEC 61850-7-4 §7.3.38");

        ln("PVPH",  "Volt/Hz (Sobreflujaje)",       "Volts-per-Hertz Protection",
           "Protege transformadores y generadores contra sobreflujaje (excitación excesiva) " +
           "detectando una relación V/Hz superior al ajuste.",
           "IEC 61850-7-4 §7.3.35");

        ln("PTOF",  "Subfrecuencia",                "Time Overfrequency Protection",
           "Actúa cuando la frecuencia del sistema supera el ajuste. " +
           "Usado en generadores y esquemas de control de frecuencia.",
           "IEC 61850-7-4 §7.3.29");

        ln("PTTR",  "Protección Térmica",           "Thermal Overload Protection",
           "Modela el calentamiento del equipo (transformador, cable, motor) " +
           "y actúa antes de que la temperatura dañe el aislamiento.",
           "IEC 61850-7-4 §7.3.32");

        // ── Protección relacionada (R) ───────────────────────────────────────
        ln("RBRF",  "Fallo del Disyuntor",          "Breaker Failure Protection",
           "Si el disyuntor no abre tras recibir la orden de disparo, inicia " +
           "un disparo de respaldo sobre los disyuntores adyacentes para aislar la falta.",
           "IEC 61850-7-4 §7.5.2",
           "RBRF1.Op (operación de respaldo), RBRF1.StrBF (arranque fallo disyuntor)");

        ln("RREC",  "Recierre Automático",          "Automatic Recloser",
           "Tras un disparo de protección, intenta reponer la línea automáticamente " +
           "con 1, 2 o 3 intentos de recierre (monofásico o trifásico) y tiempos muertos " +
           "configurables.",
           "IEC 61850-7-4 §7.5.5",
           "RREC1.Op (recierre exitoso), RREC1.Str (arranque), RREC1.Auto (habilitado)");

        ln("RSYN",  "Verificación de Sincronismo",  "Synchrocheck",
           "Verifica que la diferencia de tensión, frecuencia y ángulo entre los dos " +
           "lados de un interruptor sea aceptable antes de autorizar el cierre.",
           "IEC 61850-7-4 §7.5.6");

        ln("RPSB",  "Bloqueo Oscilaciones",         "Power Swing Blocking",
           "Bloquea la protección de distancia durante oscilaciones de potencia " +
           "para evitar disparos intempestivos.",
           "IEC 61850-7-4 §7.5.4");

        ln("RDRE",  "Registrador de Disturbios",    "Disturbance Recorder",
           "Captura formas de onda de tensión y corriente antes y después de un evento. " +
           "Almacena registros en formato COMTRADE para análisis post-falta.",
           "IEC 61850-7-4 §7.5.3",
           "RDRE1.Op (disparo del registro), RDRE1.ChNum (número de canales)");

        ln("RFLO",  "Localizador de Faltas",        "Fault Locator",
           "Calcula la distancia a la falta en una línea de transmisión usando " +
           "las medidas de tensión y corriente en el momento del evento.",
           "IEC 61850-7-4 §7.5.7");

        // ── Medición (M) ─────────────────────────────────────────────────────
        ln("MMXU",  "Medición Eléctrica",           "Measurements",
           "Nodo de medición principal: tensiones de fase y fase-fase, corrientes de fase, " +
           "potencias activa/reactiva/aparente trifásicas, factor de potencia y frecuencia. " +
           "Es el nodo más usado en contadores y relés de medición.",
           "IEC 61850-7-4 §9.1",
           "MMXU1.PhV (tensión por fases WYE), MMXU1.A (corriente WYE), " +
           "MMXU1.TotW (potencia activa total), MMXU1.Hz (frecuencia)");

        ln("MMXN",  "Medición No Trifásica",        "Non-Phase-Related Measurements",
           "Medición de magnitudes eléctricas que no se descomponen por fase: " +
           "corriente de neutro, tensión de secuencia cero, etc.",
           "IEC 61850-7-4 §9.2");

        ln("MHAI",  "Armónicos e Interarmónicos",   "Harmonics or Interharmonics",
           "Mide el contenido armónico e interarmónico de tensiones y corrientes " +
           "por fase (hasta el armónico 50). Incluye THD y factores de distorsión. " +
           "Crucial para análisis de calidad de energía.",
           "IEC 61850-7-4 §9.4",
           "MHAI1.ThdA (THD de corriente), MHAI1.HKf (orden del armónico dominante)");

        ln("MHAN",  "Armónicos No Trifásicos",      "Harmonics Not Phase-Related",
           "Similar a MHAI pero para magnitudes sin descomposición por fase " +
           "(neutro, secuencia cero).",
           "IEC 61850-7-4 §9.5");

        ln("MSQI",  "Desequilibrio y Secuencia",    "Sequence and Imbalance",
           "Calcula componentes simétricas (secuencias positiva, negativa y cero) " +
           "de tensiones y corrientes, y los índices de desequilibrio.",
           "IEC 61850-7-4 §9.7",
           "MSQI1.SeqA (secuencia de corriente), MSQI1.SeqV (secuencia de tensión)");

        ln("MMTR",  "Medidor de Energía",           "Metering",
           "Integra energía activa, reactiva y aparente (importada y exportada). " +
           "Los contadores son de tipo BCR (Binary Counter Reading), FC=ST.",
           "IEC 61850-7-4 §9.3",
           "MMTR1.TotWh (energía activa Wh), MMTR1.TotVArh, MMTR1.TotVAh");

        ln("MSTA",  "Demanda",                      "Demand Measurements",
           "Calcula valores de demanda (media en intervalo de tiempo): potencia media, " +
           "máxima demanda, factor de carga, etc.",
           "IEC 61850-7-4 §9.8");

        ln("MDIF",  "Monitoreo Diferencial",        "Monitoring Differential",
           "Monitorea la diferencia de corriente entre dos puntos sin función de disparo. " +
           "Usado para supervisión continua de integridad.",
           "IEC 61850-7-4 §9.6");

        ln("MFLK",  "Flicker",                      "Flicker Measurement",
           "Mide el parpadeo de tensión (flicker) según IEC 61000-4-15: " +
           "Pst (corto plazo, 10 min) y Plt (largo plazo, 2 horas).",
           "IEC 61850-7-4 §9.9");

        // ── Control automático (A) ────────────────────────────────────────────
        ln("ATCC",  "Control de Tap",               "Tap Changer Controller",
           "Controla el cambiador de derivaciones (tap changer) de un transformador " +
           "para regular la tensión en el lado secundario dentro de una banda de ajuste.",
           "IEC 61850-7-4 §6.1.1",
           "ATCC1.TapPos (posición actual del tap), ATCC1.Auto (modo automático)");

        ln("AVCO",  "Control de Tensión",           "Voltage Control",
           "Coordina múltiples transformadores con tap changer o bancos de capacitores " +
           "para regular la tensión en una barra.",
           "IEC 61850-7-4 §6.1.2");

        ln("ARCO",  "Control Reactivo",             "Reactive Power Control",
           "Regula la potencia reactiva inyectada o absorbida (bancos de condensadores, " +
           "SVCs, generadores) para mantener la tensión y el factor de potencia.",
           "IEC 61850-7-4 §6.1.3");

        // ── Genéricos (G) ────────────────────────────────────────────────────
        ln("GAPC",  "Control Automático Genérico",  "Generic Automatic Process Control",
           "Nodo genérico para lógica de control de proceso no cubierta por otros LN. " +
           "Tiene entradas/salidas digitales y analógicas genéricas.",
           "IEC 61850-7-4 §10.1");

        ln("GGIO",  "I/O Genérico",                 "Generic Process I/O",
           "Expone entradas y salidas digitales/analógicas de propósito general del IED. " +
           "Muy usado para señales de estado, alarmas y comandos misceláneos.",
           "IEC 61850-7-4 §10.2",
           "GGIO1.Ind1...Ind16 (indicaciones digitales), GGIO1.AnIn1 (entrada analógica)");

        ln("GSAL",  "Alarma de Seguridad",          "Security Alarm",
           "Genera alarmas relacionadas con la seguridad cibernética del IED: " +
           "accesos no autorizados, integridad de configuración, etc.",
           "IEC 61850-7-4 §10.3");

        // ── Transformador (Y) ─────────────────────────────────────────────────
        ln("YTRF",  "Transformador de Potencia",    "Power Transformer",
           "Modela el transformador de potencia: relación de transformación, grupo vectorial, " +
           "temperatura, nivel de aceite, gas disuelto (DGA), etc.",
           "IEC 61850-7-4 §11.2",
           "YTRF1.NamPlt (placa), YTRF1.TmpHot (temperatura punto caliente)");

        ln("YLTC",  "Cambiador de Tap",             "Tap Changer (OLTC)",
           "Representa el cambiador de derivaciones en carga (On-Load Tap Changer). " +
           "Informa la posición actual, límites y contador de operaciones.",
           "IEC 61850-7-4 §11.3",
           "YLTC1.TapPos (posición), YLTC1.TapChg (cambio de tap)");

        ln("YEFN",  "Neutro de Falta a Tierra",     "Earth Fault Neutralizer",
           "Controla la bobina Petersen (reactancia de puesta a tierra resonante) " +
           "para compensar la corriente capacitiva en redes con neutro aislado/compensado.",
           "IEC 61850-7-4 §11.1");

        ln("YPSH",  "Shunt de Potencia",            "Power Shunt",
           "Representa un banco de capacitores, reactor en shunt u otro elemento " +
           "conectado en paralelo para compensación reactiva.",
           "IEC 61850-7-4 §11.4");

        // ── Equipo de sistema de potencia (Z) ────────────────────────────────
        ln("ZLIN",  "Línea de Transmisión",         "Transmission Line",
           "Modela la línea de transmisión: longitud, impedancia, capacitancia, " +
           "corriente nominal. Sirve como referencia para el localizador de faltas.",
           "IEC 61850-7-4 §12.3");

        ln("ZGEN",  "Generador",                    "Generator",
           "Modela el generador eléctrico: potencia nominal, factor de potencia nominal, " +
           "tensión, velocidad. Puede incluir curva de capabilidad.",
           "IEC 61850-7-4 §12.2");

        ln("ZMOT",  "Motor Eléctrico",              "Electric Motor",
           "Modela un motor eléctrico: corriente nominal, factor de potencia, " +
           "temperatura de bobinado, número de arranques.",
           "IEC 61850-7-4 §12.4");

        ln("ZCAP",  "Banco de Capacitores",         "Capacitor Bank",
           "Representa un banco de capacitores para compensación reactiva en shunt. " +
           "Incluye número de escalones, reactivo nominal y estado de cada escalón.",
           "IEC 61850-7-4 §12.7");

        ln("ZBAT",  "Batería / UPS",                "Battery / UPS",
           "Monitorea la batería de respaldo o UPS del IED o de la subestación: " +
           "voltaje, corriente, estado de carga, temperatura.",
           "IEC 61850-7-4 §12.1");

        ln("ZSAR",  "Pararrayos",                   "Surge Arrester",
           "Modela el pararrayos de óxido de zinc. Puede incluir contador de descargas, " +
           "corriente de fuga y temperatura.",
           "IEC 61850-7-4 §12.6");

        // ── Transformadores de medida (T) ─────────────────────────────────────
        ln("TCTR",  "Transformador de Corriente",   "Current Transformer",
           "Modela el TC: relación de transformación, clase de precisión, carga nominal, " +
           "factor de saturación. Necesario para modelos de subestación precisos.",
           "IEC 61850-7-4 §13.1",
           "TCTR1.Rat (relación), TCTR1.A (corriente secundaria medida)");

        ln("TVTR",  "Transformador de Tensión",     "Voltage Transformer",
           "Modela el TT/TP: relación de transformación, clase de precisión, " +
           "carga nominal y factor de tensión.",
           "IEC 61850-7-4 §13.2",
           "TVTR1.Rat (relación), TVTR1.Vol (tensión secundaria medida)");

        // ── Supervisión (S) ──────────────────────────────────────────────────
        ln("STMP",  "Temperatura",                  "Temperature Supervision",
           "Supervisa temperaturas en puntos del equipo (aceite, bobinado, ambiente, " +
           "punto caliente) con alarmas y disparos por sobretemperatura.",
           "IEC 61850-7-4 §14.4");

        ln("SCBR",  "Supervisión Disyuntor",        "Circuit Breaker Supervision",
           "Supervisa el estado mecánico y eléctrico del disyuntor: presión de SF6, " +
           "presión de resorte, nivel de aceite, desgaste de contactos.",
           "IEC 61850-7-4 §14.1");

        ln("SIMG",  "Gas SF6",                      "Insulating Medium Supervision (Gas)",
           "Supervisa el gas SF6 de cámaras de interrupción: presión, densidad, " +
           "temperatura y alarmas de bajo nivel.",
           "IEC 61850-7-4 §14.2");

        ln("SOPM",  "Supervisión de Operación",     "Operation Supervision",
           "Supervisa parámetros de operación: contador de disparos, corriente de " +
           "interrupción acumulada, número de operaciones en vacío.",
           "IEC 61850-7-4 §14.3");

        // ═══════════════════════════════════════════════════════════════════════
        //  Functional Constraints (FC) — IEC 61850-7-2
        // ═══════════════════════════════════════════════════════════════════════
        fc("ST",  "Estado (Status)",
           "Valores de estado del proceso en tiempo real. Son los datos más leídos: " +
           "posición de interruptores, alarmas, calidad e indicaciones de protección. " +
           "Accesibles por el cliente y reportados automáticamente mediante RCB.",
           "IEC 61850-7-2 §6.2",
           "XCBR1.Pos [ST], MMXU1.TotW [ST]");

        fc("MX",  "Valores Medidos (Measured Values)",
           "Valores analógicos de proceso: tensión, corriente, potencia, frecuencia, etc. " +
           "Se actualizan periódicamente o por cambio significativo. " +
           "Reportados mediante RCB o leídos por polling.",
           "IEC 61850-7-2 §6.2",
           "MMXU1.PhV [MX], MMXU1.A [MX], MMXU1.Hz [MX]");

        fc("CO",  "Control (Control)",
           "Atributos usados en secuencias de control: valor de control (ctlVal), " +
           "origen (origin), número de secuencia (ctlNum), tiempo de operación (operTm). " +
           "El cliente escribe estos atributos para enviar comandos al IED.",
           "IEC 61850-7-2 §6.2",
           "XCBR1.Pos.ctlVal [CO] — envía comando open/close");

        fc("CF",  "Configuración (Configuration)",
           "Parámetros de configuración del nodo lógico: deadband, período de integridad, " +
           "constantes de tiempo. Normalmente escritos durante la puesta en servicio.",
           "IEC 61850-7-2 §6.2",
           "MMXU1.PhV.db [CF] — deadband para reportes por cambio");

        fc("DC",  "Descripción / Placa (Description)",
           "Datos de placa (nameplate) del LN: fabricante, modelo, versión de software, " +
           "número de serie. De solo lectura. Acceso típico: F=DC o F=LPL.",
           "IEC 61850-7-2 §6.2",
           "XCBR1.NamPlt [DC]");

        fc("SG",  "Grupo de Ajuste Activo (Setting Group)",
           "Valores del grupo de ajustes actualmente activo. El cliente puede leer " +
           "los ajustes vigentes pero no modificarlos aquí (usar SE para editar).",
           "IEC 61850-7-2 §6.2",
           "PTOC1.StrVal [SG] — umbral de arranque del ajuste activo");

        fc("SE",  "Edición de Grupo Ajustes (Setting Group Edit)",
           "Acceso al grupo de ajustes en edición. El cliente puede modificar " +
           "estos valores antes de activarlos con el comando ConfSG.",
           "IEC 61850-7-2 §6.2");

        fc("SP",  "Set Point",
           "Punto de ajuste que el cliente puede modificar directamente (sin selección previa). " +
           "A diferencia de SG, es un valor operacional, no un grupo de ajustes.",
           "IEC 61850-7-2 §6.2");

        fc("EX",  "Extensión (Extended Definition)",
           "Atributos de definición extendida, específicos del fabricante o de la aplicación. " +
           "No forman parte del modelo normativo estándar.",
           "IEC 61850-7-2 §6.2");

        fc("OR",  "Operate Received",
           "Indica que se recibió una orden de operación. Usado internamente para " +
           "seguimiento de comandos en secuencias SBO (Select-Before-Operate).",
           "IEC 61850-7-2 §6.2");

        fc("BL",  "Bloqueo (Blocking)",
           "Bloquea el valor de un atributo impidiendo su actualización. " +
           "Se usa en pruebas de mantenimiento (blkEna=true). " +
           "El valor congelado permanece aunque cambie el proceso.",
           "IEC 61850-7-2 §6.2");

        // ═══════════════════════════════════════════════════════════════════════
        //  Common Data Classes (CDC) — IEC 61850-7-3
        // ═══════════════════════════════════════════════════════════════════════
        cdc("SPS",  "Estado Simple (Single Point Status)",
            "Bit booleano de estado del proceso: VERDADERO o FALSO. " +
            "Contiene: stVal (valor), q (calidad) y t (timestamp). " +
            "Ejemplo: disyuntor enclavado, señal de alarma, habilitación de modo.",
            "IEC 61850-7-3 §7.3.1",
            "BlkOpn, BlkCls, Loc, Auto, EnaOpn, EnaCls");

        cdc("DPS",  "Estado Doble (Double Point Status)",
            "Estado de proceso con cuatro valores: INTERMEDIO (00), ABIERTO (01), " +
            "CERRADO (10), DEFECTO (11). Es el CDC estándar para posición de " +
            "interruptores y seccionadores.",
            "IEC 61850-7-3 §7.3.2",
            "Pos (posición de XCBR/XSWI) — intermedio durante la maniobra");

        cdc("INS",  "Entero de Estado (Integer Status)",
            "Valor entero de estado del proceso con calidad y timestamp. " +
            "Usado para posiciones de tap, contadores de operaciones, etc.",
            "IEC 61850-7-3 §7.3.3",
            "TapPos (posición de cambiador de tap), OpCnt (contador)");

        cdc("ENS",  "Enumerado de Estado (Enumerated Status)",
            "Valor enumerado de estado: modo de operación (on/blocked/test/off), " +
            "estado de salud (OK/Warning/Alarm), comportamiento del LN.",
            "IEC 61850-7-3 §7.3.4",
            "Mod (modo: on/blocked/test/test-blocked/off), Beh, Health");

        cdc("ACT",  "Actuación de Protección (Protection Activation)",
            "Indica que una función de protección ha actuado. " +
            "Incluye: general (general), por fase (phsA/phsB/phsC) y dirección.",
            "IEC 61850-7-3 §7.3.5",
            "Op (operación de disparo), Str (arranque)");

        cdc("ACD",  "Dirección de Protección (Directional Protection Activation)",
            "Extiende ACT con información de direccionalidad: forward, backward, both. " +
            "Usado en protecciones direccionales (distancia, direccional de tierra).",
            "IEC 61850-7-3 §7.3.6");

        cdc("SEC",  "Contador de Eventos de Seguridad","Security Event Counter",
            "Contador con calidad y timestamp para eventos de seguridad. " +
            "Usado en nodos GSAL.",
            "IEC 61850-7-3 §7.3.7");

        cdc("BCR",  "Contador de Energía (Binary Counter Reading)",
            "Lectura de contador binario acumulador: actVal (valor acumulado INT64), " +
            "frVal (valor fraccionario), q (calidad) y t (timestamp de la lectura). " +
            "FC=ST. Dividir por 1000 para convertir Wh → kWh.",
            "IEC 61850-7-3 §7.3.8",
            "TotWh.actVal [ST] / 1000 = kWh");

        cdc("MV",   "Valor Medido (Measured Value)",
            "Valor analógico escalar: mag.f (flotante) o mag.i (entero), " +
            "q (calidad) y t (timestamp). El más simple para una magnitud sin fase.",
            "IEC 61850-7-3 §7.3.9",
            "TotW, TotVAr, Hz, PF → todos son MV");

        cdc("CMV",  "Valor Medido Complejo (Complex Measured Value)",
            "Valor fasorial: magnitud (mag.f) y ángulo (ang.f). " +
            "Usado para representar fasores de tensión y corriente individualmente.",
            "IEC 61850-7-3 §7.3.10",
            "Componentes de SEQ: c1, c2, c0 son CMV");

        cdc("SAV",  "Valor Muestreado (Sampled Value)",
            "Muestra instantánea de tensión o corriente, usada principalmente " +
            "en Sampled Values (SVs). Incluye instVal (valor instantáneo).",
            "IEC 61850-7-3 §7.3.11");

        cdc("WYE",  "Trifásico Estrella (AC Wye)",
            "Agrupa tres fasores de fase (phsA, phsB, phsC) y el neutro (neut), " +
            "cada uno de tipo CMV. Es el CDC estándar para tensiones de fase y " +
            "corrientes de línea en conexión estrella.",
            "IEC 61850-7-3 §7.3.12",
            "PhV (tensiones de fase), A (corrientes de línea), W, VAr, VA, PF");

        cdc("DEL",  "Trifásico Delta (AC Delta)",
            "Agrupa tres fasores fase-fase (phsAB, phsBC, phsCA), cada uno CMV. " +
            "Usado para tensiones de línea (fase a fase) en conexión delta.",
            "IEC 61850-7-3 §7.3.13",
            "PPV (tensión fase-fase)");

        cdc("SEQ",  "Componentes Simétricas (AC Sequence)",
            "Descomposición simétrica: c1 (secuencia positiva), c2 (negativa), " +
            "c0 (secuencia cero), cada componente de tipo CMV con magnitud y ángulo.",
            "IEC 61850-7-3 §7.3.14",
            "SeqV.c1 (tensión secuencia positiva), SeqA.c2 (corriente negativa)");

        cdc("SPC",  "Controlable Punto Simple (Single Point Controllable)",
            "Punto de control booleano: el cliente envía ctlVal=TRUE/FALSE. " +
            "El proceso responde cambiando stVal. CDC para salidas digitales simples.",
            "IEC 61850-7-3 §7.4.1");

        cdc("DPC",  "Controlable Punto Doble (Double Point Controllable)",
            "Control de posición: el cliente ordena CLOSE (01) u OPEN (10). " +
            "La respuesta incluye los estados intermedios. " +
            "CDC estándar para comandos a interruptores/seccionadores.",
            "IEC 61850-7-3 §7.4.2",
            "Pos de XCBR/XSWI — stVal es DPS, ctlVal es SPC (open/close)");

        cdc("INC",  "Controlable Entero (Integer Controllable)",
            "Permite al cliente escribir un valor entero. Usado para cambios de tap " +
            "manuales, ajuste de número de grupo de ajuste, etc.",
            "IEC 61850-7-3 §7.4.3",
            "TapPos con control, SetGrp (selección de grupo de ajuste)");

        cdc("ENC",  "Controlable Enumerado (Enumerated Controllable)",
            "Control por enumeración: el cliente envía uno de los valores definidos " +
            "en el modelo. Típico para Mod (modo del LN).",
            "IEC 61850-7-3 §7.4.4",
            "Mod.ctlVal = on/blocked/test/test-blocked/off");

        cdc("BSC",  "Control por Pasos Binario (Binary Step Control)",
            "Control de posición por pasos: comandos 'subir' (higher) o 'bajar' (lower). " +
            "Usado para tap changer controlado en escalones.",
            "IEC 61850-7-3 §7.4.5");

        cdc("APC",  "Controlable Analógico (Analogue Controllable)",
            "Permite al cliente escribir un valor analógico (float). " +
            "Usado para set-points de reguladores: tensión objetivo, potencia, etc.",
            "IEC 61850-7-3 §7.4.7");

        cdc("BAC",  "Control Analógico Binario (Binary Controlled Analogue Set Point)",
            "Set point analógico controlado por pasos binarios (subir/bajar). " +
            "Combina APC y BSC.",
            "IEC 61850-7-3 §7.4.8");

        cdc("LPL",  "Placa del Nodo Lógico (Logical Node Name Plate)",
            "Datos de identificación del LN o del equipo físico: " +
            "fabricante (vendor), modelo (swRev, hwRev), número de serie (serNum), " +
            "descripción (d), localización (loc), tipo de IED (model).",
            "IEC 61850-7-3 §7.5.1",
            "NamPlt de LLN0 y LPHD");

        cdc("DPL",  "Placa del Dispositivo (Device Name Plate)",
            "Datos de placa del dispositivo físico o componente de campo: " +
            "fabricante, número de serie, tipo, tensión/corriente nominal.",
            "IEC 61850-7-3 §7.5.2");

        // ═══════════════════════════════════════════════════════════════════════
        //  Data Objects (DO) comunes — IEC 61850-7-4
        // ═══════════════════════════════════════════════════════════════════════
        doEntry("Pos",    "Posición",              "Position",
            "Posición del aparato de corte (DPS): INTERMEDIO/ABIERTO/CERRADO/DEFECTO. " +
            "Para XCBR y XSWI. También es el DO de control (ctlVal) cuando FC=CO.",
            "IEC 61850-7-4", "Pos [ST] = on → cerrado; off → abierto");

        doEntry("NamPlt", "Placa de Datos",        "Name Plate",
            "Placa de identificación del nodo lógico o equipo (CDC LPL). " +
            "Contiene vendor, swRev, hwRev, serNum, model. FC=DC.",
            "IEC 61850-7-4");

        doEntry("Health", "Estado de Salud",       "Health",
            "Estado de salud del nodo lógico: OK (1), Warning (2), Alarm (3). " +
            "CDC ENS. Refleja si el LN funciona correctamente.",
            "IEC 61850-7-4");

        doEntry("Mod",    "Modo",                  "Mode",
            "Modo de operación del LN (CDC ENC): on, blocked, test, test-blocked, off. " +
            "El cliente puede cambiarlo con ctlVal. FC=CF para el ajuste, CO para el comando.",
            "IEC 61850-7-4");

        doEntry("Beh",    "Comportamiento",        "Behaviour",
            "Comportamiento real del nodo lógico (CDC ENS). Refleja el estado efectivo " +
            "considerando el Mod y posibles bloqueos externos.",
            "IEC 61850-7-4");

        doEntry("Loc",    "Operación Local",       "Local",
            "Indica si el equipo está en modo de operación local (TRUE) o remoto (FALSE). " +
            "CDC SPS. Cuando está en LOCAL, los comandos remotos pueden ser rechazados.",
            "IEC 61850-7-4",
            "Loc [ST] = TRUE → solo se acepta operación desde el panel local");

        doEntry("BlkOpn", "Bloqueo de Apertura",  "Block Open",
            "Bloquea la apertura del interruptor (SPS). Cuando TRUE, el enclavamiento " +
            "CILO rechaza comandos de apertura.",
            "IEC 61850-7-4");

        doEntry("BlkCls", "Bloqueo de Cierre",    "Block Close",
            "Bloquea el cierre del interruptor (SPS). Cuando TRUE, el enclavamiento " +
            "CILO rechaza comandos de cierre.",
            "IEC 61850-7-4");

        doEntry("EnaOpn", "Habilitar Apertura",   "Enable Open",
            "Señal de habilitación de apertura desde CILO. TRUE = la apertura está permitida.",
            "IEC 61850-7-4");

        doEntry("EnaCls", "Habilitar Cierre",     "Enable Close",
            "Señal de habilitación de cierre desde CILO. TRUE = el cierre está permitido.",
            "IEC 61850-7-4");

        doEntry("Auto",   "Modo Automático",      "Automatic Mode",
            "Indica si el equipo opera en modo automático (TRUE) o manual (FALSE). SPS.",
            "IEC 61850-7-4");

        doEntry("Op",     "Operación / Disparo",  "Operation / Trip",
            "Indica que la función de protección ha operado (disparado). CDC ACT. " +
            "Incluye general, phsA, phsB, phsC y neut.",
            "IEC 61850-7-4",
            "PTOC1.Op.general [ST] = TRUE → protección disparó");

        doEntry("Str",    "Arranque",             "Start",
            "Indica que la función de protección ha arrancado (supera umbral, inicia temporización). " +
            "CDC ACT/ACD. Precede a Op si se mantiene la condición.",
            "IEC 61850-7-4",
            "PDIS1.Str.general [ST] = TRUE → impedancia dentro de zona");

        doEntry("PhV",    "Tensión de Fase",      "Phase Voltage",
            "Tensiones de fase respecto al neutro (CDC WYE). phsA, phsB, phsC, neut. " +
            "Cada componente es un CMV con magnitud (mag.f en V) y ángulo (ang.f en °).",
            "IEC 61850-7-4",
            "MMXU1.PhV.phsA.mag.f [MX] = 127000 V (127 kV en secundario de TT)");

        doEntry("PPV",    "Tensión Fase-Fase",    "Phase-to-Phase Voltage",
            "Tensiones entre fases (CDC DEL): phsAB, phsBC, phsCA. CMV. " +
            "En un sistema de 220 kV, PPV ≈ PhV × √3.",
            "IEC 61850-7-4");

        doEntry("A",      "Corriente de Línea",   "Line Current",
            "Corrientes de línea de las tres fases (CDC WYE): phsA, phsB, phsC, neut. " +
            "CMV con magnitud en A y ángulo en °.",
            "IEC 61850-7-4",
            "MMXU1.A.phsA.mag.f [MX] = corriente en fase A");

        doEntry("W",      "Potencia Activa",      "Active Power",
            "Potencia activa trifásica por fase (CDC WYE). En MW. " +
            "phsA + phsB + phsC ≈ TotW.",
            "IEC 61850-7-4");

        doEntry("VAr",    "Potencia Reactiva",    "Reactive Power",
            "Potencia reactiva trifásica por fase (CDC WYE). En MVAr. " +
            "Positivo = inductivo (consumo), negativo = capacitivo (generación).",
            "IEC 61850-7-4");

        doEntry("VA",     "Potencia Aparente",    "Apparent Power",
            "Potencia aparente trifásica por fase (CDC WYE). En MVA. " +
            "VA = √(W² + VAr²).",
            "IEC 61850-7-4");

        doEntry("TotW",   "Potencia Activa Total", "Total Active Power",
            "Potencia activa total del sistema trifásico (CDC MV). En W. " +
            "Escalar resultante de la suma de las tres fases.",
            "IEC 61850-7-4",
            "MMXU1.TotW.mag.f [MX] = W (dividir por 1e6 para MW)");

        doEntry("TotVAr", "Potencia Reactiva Total","Total Reactive Power",
            "Potencia reactiva total del sistema trifásico (CDC MV). En VAr.",
            "IEC 61850-7-4");

        doEntry("TotVA",  "Potencia Aparente Total","Total Apparent Power",
            "Potencia aparente total del sistema trifásico (CDC MV). En VA.",
            "IEC 61850-7-4");

        doEntry("TotPF",  "Factor de Potencia Total","Total Power Factor",
            "Factor de potencia total (CDC MV). Adimensional, rango [-1, +1]. " +
            "PF = W / VA. Positivo = inductivo.",
            "IEC 61850-7-4");

        doEntry("Hz",     "Frecuencia",           "Frequency",
            "Frecuencia del sistema eléctrico (CDC MV). En Hz. " +
            "Nominal: 50 Hz (Europa/AM del Sur) o 60 Hz (América del Norte).",
            "IEC 61850-7-4",
            "MMXU1.Hz.mag.f [MX] = 50.02 Hz");

        doEntry("PF",     "Factor de Potencia",   "Power Factor",
            "Factor de potencia por fase (CDC WYE). phsA, phsB, phsC. " +
            "Complementa TotPF con descomposición por fase.",
            "IEC 61850-7-4");

        doEntry("TotWh",  "Energía Activa",       "Active Energy",
            "Energía activa acumulada (CDC BCR). actVal en Wh (INT64). " +
            "FC=ST. Dividir por 1000 para obtener kWh.",
            "IEC 61850-7-4",
            "MMTR1.TotWh.actVal [ST] / 1000 = kWh importados");

        doEntry("TotVArh","Energía Reactiva",     "Reactive Energy",
            "Energía reactiva acumulada (CDC BCR). actVal en VArh.",
            "IEC 61850-7-4");

        doEntry("TotVAh", "Energía Aparente",     "Apparent Energy",
            "Energía aparente acumulada (CDC BCR). actVal en VAh.",
            "IEC 61850-7-4");

        doEntry("SeqA",   "Secuencia de Corriente","Current Sequence Components",
            "Componentes simétricas de la corriente (CDC SEQ): " +
            "c1 (positiva), c2 (negativa), c0 (cero). CMV con magnitud y ángulo.",
            "IEC 61850-7-4",
            "MSQI1.SeqA.c2.mag.f → corriente de secuencia negativa (desequilibrio)");

        doEntry("SeqV",   "Secuencia de Tensión", "Voltage Sequence Components",
            "Componentes simétricas de la tensión (CDC SEQ): c1, c2, c0.",
            "IEC 61850-7-4");

        doEntry("ThdA",   "THD Corriente",        "Total Harmonic Distortion (Current)",
            "Distorsión armónica total de la corriente (CDC WYE o MV). " +
            "Expresado en % respecto al fundamental. Límite típico: 5% (IEEE 519).",
            "IEC 61850-7-4",
            "MHAI1.ThdA.phsA.mag.f [MX] = 8.5 (%)");

        doEntry("ThdPhV", "THD Tensión de Fase",  "Total Harmonic Distortion (Phase Voltage)",
            "Distorsión armónica total de la tensión de fase (CDC WYE). " +
            "Límite típico: 5% (IEEE 519) o 8% (IEC 61000-2-2).",
            "IEC 61850-7-4");

        doEntry("TapPos", "Posición del Tap",     "Tap Position",
            "Posición actual del cambiador de derivaciones (CDC INS o INC). " +
            "Valor entero: 0 = posición media, positivo = sube tensión.",
            "IEC 61850-7-4",
            "YLTC1.TapPos.stVal [ST] = 3 (tercer tap por encima del centro)");

        doEntry("OpCnt",  "Contador de Operaciones","Operation Counter",
            "Contador acumulado de operaciones de apertura/cierre (CDC INS). " +
            "Usado para planificación de mantenimiento.",
            "IEC 61850-7-4");

        doEntry("OpDlTmms","Tiempo de Apertura",  "Opening Delay Time",
            "Tiempo de apertura del disyuntor en milisegundos (CDC MV). " +
            "Aumenta cuando los contactos se desgastan.",
            "IEC 61850-7-4");

        doEntry("CBOpCap","Capacidad de Interrupción","CB Operating Capability",
            "Capacidad de interrupción residual del disyuntor en % (CDC MV). " +
            "Reduce con cada operación en carga.",
            "IEC 61850-7-4");

        // ═══════════════════════════════════════════════════════════════════════
        //  Data Attributes (DA) comunes — IEC 61850-7-3
        // ═══════════════════════════════════════════════════════════════════════
        da("stVal",   "Valor de Estado",          "Status Value",
           "Valor actual del dato de proceso: boolean (SPS), entero (DPS/INS), " +
           "float (MV) o enumerado. Es el atributo de lectura principal del CDC.",
           "IEC 61850-7-3",
           "Pos.stVal = on (cerrado), TotW.mag.f = 125000 (W)");

        da("q",       "Calidad",                  "Quality",
           "Indicadores de calidad del valor: validity (good/invalid/reserved/questionable), " +
           "overflow, outOfRange, badReference, oscillatory, failure, oldData, " +
           "inconsistent, inaccurate, source (process/substituted), test, operatorBlocked.",
           "IEC 61850-7-3",
           "q.validity = good → valor confiable");

        da("t",       "Timestamp",                "Timestamp",
           "Marca de tiempo del último cambio del valor. " +
           "Resolución de 1 ms, incluye información de calidad del reloj " +
           "(clockNotSynchronized, clockFailure, etc.).",
           "IEC 61850-7-3",
           "t = 2026-04-23T14:30:00.123Z");

        da("mag",     "Magnitud",                 "Magnitude",
           "Componente de magnitud de un CMV (Complex Measured Value). " +
           "Contiene f (float) o i (integer).",
           "IEC 61850-7-3",
           "PhV.phsA.mag.f = 63508.5 (V)");

        da("ang",     "Ángulo",                   "Angle",
           "Componente de ángulo de un CMV en grados (°). " +
           "Referenciado al fasor de tensión de fase A por convención.",
           "IEC 61850-7-3",
           "A.phsA.ang.f = -32.5 (°) → desfase de la corriente respecto a tensión");

        da("f",       "Flotante",                 "Float",
           "Atributo de punto flotante (FLOAT32). " +
           "Hijo de mag o ang en fasores (CMV), o de instVal en SAV.",
           "IEC 61850-7-3");

        da("i",       "Entero",                   "Integer",
           "Atributo entero (INT32). Alternativa a f en magnitudes enteras " +
           "(por ejemplo, contadores, posiciones de tap).",
           "IEC 61850-7-3");

        da("ctlVal",  "Valor de Control",         "Control Value",
           "Valor que el cliente envía para comandar el proceso: " +
           "boolean (SPC/DPC), entero (INC) o float (APC). " +
           "FC=CO. Es el núcleo de la secuencia de control.",
           "IEC 61850-7-3",
           "Pos.ctlVal = true → comando de cierre");

        da("origin",  "Origen del Comando",       "Command Origin",
           "Indica quién emitió el comando: orCat (categoría: not-supported/bay-control/" +
           "station-control/remote-control/automatic-bay/...) y orIdent (identificación).",
           "IEC 61850-7-3",
           "origin.orCat = remote-control → comando desde SCADA");

        da("ctlNum",  "Número de Control",        "Control Number",
           "Número de secuencia del control (0-255). El servidor lo devuelve en la " +
           "respuesta para que el cliente identifique a qué comando pertenece.",
           "IEC 61850-7-3");

        da("operTm",  "Tiempo de Operación",      "Operate Time",
           "Hora programada para ejecutar el control (time-activated control). " +
           "Si es cero, el control es inmediato.",
           "IEC 61850-7-3");

        da("actVal",  "Valor Acumulado",          "Actual (Accumulated) Value",
           "Valor acumulado de un contador BCR (INT64). " +
           "Para energía: actVal en Wh, dividir por 1000 para kWh.",
           "IEC 61850-7-3",
           "TotWh.actVal = 12345678 Wh = 12345.678 kWh");

        da("frVal",   "Valor Fraccionario",       "Fractional Value",
           "Fracción decimal del contador BCR para alta resolución. " +
           "actVal + frVal/2^32 = valor real del contador.",
           "IEC 61850-7-3");

        da("vendor",  "Fabricante",               "Vendor",
           "Nombre del fabricante del IED o componente. Atributo de NamPlt (LPL). FC=DC.",
           "IEC 61850-7-3",
           "NamPlt.vendor = 'Siemens AG'");

        da("swRev",   "Versión de Software",      "Software Revision",
           "Versión del firmware/software del IED. Atributo de NamPlt. FC=DC.",
           "IEC 61850-7-3");

        da("hwRev",   "Versión de Hardware",      "Hardware Revision",
           "Versión del hardware del IED. Atributo de NamPlt. FC=DC.",
           "IEC 61850-7-3");

        da("serNum",  "Número de Serie",          "Serial Number",
           "Número de serie único del equipo. Atributo de NamPlt. FC=DC.",
           "IEC 61850-7-3");

        da("model",   "Modelo",                   "Model",
           "Identificación del modelo del equipo. Atributo de NamPlt. FC=DC.",
           "IEC 61850-7-3",
           "NamPlt.model = 'SIPROTEC 5 7SL86'");

        da("d",       "Descripción",              "Description",
           "Cadena de texto descriptiva del elemento (ASCII). FC=DC. " +
           "Usada para etiquetado interno del LN o DO.",
           "IEC 61850-7-3");

        da("dU",      "Descripción Unicode",      "Description (Unicode)",
           "Descripción en Unicode del LN o DO. Permite caracteres no ASCII. FC=DC.",
           "IEC 61850-7-3");

        da("db",      "Deadband",                 "Deadband",
           "Banda muerta para trigger por cambio de valor (dchg). " +
           "Especificado en el rango de la unidad de ingeniería. FC=CF.",
           "IEC 61850-7-3",
           "PhV.db = 100 → reportar si la tensión cambia más de 100 V");

        da("zeroDb",  "Zona Muerta Cero",         "Zero Dead Band",
           "Banda muerta para valores próximos a cero. FC=CF.",
           "IEC 61850-7-3");

        da("smpRate", "Tasa de Muestreo",         "Sampling Rate",
           "Tasa de muestreo de valores SAV (Sampled Values). FC=CF. " +
           "Típico: 80 o 256 muestras por ciclo de red.",
           "IEC 61850-7-3");

        da("phsA",    "Fase A",                   "Phase A",
           "Sub-atributo de WYE o DEL que contiene el fasor de la fase A (CMV). " +
           "Acceso: PhV.phsA.mag.f",
           "IEC 61850-7-3");

        da("phsB",    "Fase B",                   "Phase B",
           "Sub-atributo de WYE que contiene el fasor de la fase B (CMV).",
           "IEC 61850-7-3");

        da("phsC",    "Fase C",                   "Phase C",
           "Sub-atributo de WYE que contiene el fasor de la fase C (CMV).",
           "IEC 61850-7-3");

        da("neut",    "Neutro",                   "Neutral",
           "Sub-atributo de WYE que contiene el fasor del neutro (CMV).",
           "IEC 61850-7-3");

        da("phsAB",   "Fase AB (Delta)",          "Phase AB",
           "Tensión fase A – fase B en CDC DEL.",
           "IEC 61850-7-3");

        da("phsBC",   "Fase BC (Delta)",          "Phase BC",
           "Tensión fase B – fase C en CDC DEL.",
           "IEC 61850-7-3");

        da("phsCA",   "Fase CA (Delta)",          "Phase CA",
           "Tensión fase C – fase A en CDC DEL.",
           "IEC 61850-7-3");

        da("c1",      "Secuencia Positiva",       "Positive Sequence",
           "Componente simétrica de secuencia positiva (CMV) en CDC SEQ.",
           "IEC 61850-7-3",
           "SeqA.c1.mag.f → magnitud de la componente de secuencia positiva de corriente");

        da("c2",      "Secuencia Negativa",       "Negative Sequence",
           "Componente simétrica de secuencia negativa (CMV) en CDC SEQ. " +
           "Indica desequilibrio de carga o falta entre fases.",
           "IEC 61850-7-3");

        da("c0",      "Secuencia Cero",           "Zero Sequence",
           "Componente simétrica de secuencia cero (CMV) en CDC SEQ. " +
           "Indica corriente de falta a tierra.",
           "IEC 61850-7-3");

        da("instVal", "Valor Instantáneo",        "Instantaneous Value",
           "Valor de muestra instantánea en SAV (Sampled Value).",
           "IEC 61850-7-3");

        da("units",   "Unidades",                 "Units",
           "Unidades de ingeniería del valor: SIUnit (W, V, A, Hz, °C...) " +
           "y multiplier (kilo, mega, milli, etc.). FC=CF.",
           "IEC 61850-7-3",
           "units.SIUnit = W, units.multiplier = k → kW");

        da("general", "General (todas las fases)","General (All Phases)",
           "Atributo de ACT/ACD que indica operación en cualquier fase. " +
           "general = phsA OR phsB OR phsC.",
           "IEC 61850-7-3",
           "Str.general = TRUE → arranque de protección en alguna fase");

        // ── Nodo especial: GOOSE, DataSet, RCB ──────────────────────────────
        special("FCDA",   "Miembro de DataSet (FCDA)",
            "Functional Constrained Data Attribute — elemento de un dataset GOOSE o de reporte. " +
            "Referencia a un atributo específico del modelo con su FC: ldInst/prefixLNClass.DO.DA [FC].",
            "IEC 61850-7-2 §9.2");

        special("DSET",   "DataSet",
            "Colección de referencias a atributos del modelo (FCDAs). " +
            "Es la 'lista de variables' que se publica via GOOSE o se incluye en reportes (RCB). " +
            "Definido en el LLN0 de cada LD.",
            "IEC 61850-7-2 §9");

        special("GoCB",   "Bloque de Control GOOSE (GoCB)",
            "Parámetros del mensaje GOOSE: GoID, AppID, destMAC, confRev, dataset. " +
            "Publicado por el IED en Ethernet multicast cuando hay cambio en el dataset.",
            "IEC 61850-7-2 §9.2 / IEC 61850-8-1");

        special("URCB",   "RCB sin Buffer (Unbuffered RCB)",
            "Reporte sin buffer: el servidor envía el reporte inmediatamente. " +
            "Si el cliente no está conectado, el evento se pierde. " +
            "Solo un cliente puede suscribirse a la vez por URCB.",
            "IEC 61850-7-2 §14");

        special("BRCB",   "RCB con Buffer (Buffered RCB)",
            "Reporte con buffer: el servidor almacena los eventos aunque el cliente " +
            "esté desconectado. Al reconectar, el cliente recibe todos los reportes pendientes. " +
            "Esencial para aplicaciones críticas.",
            "IEC 61850-7-2 §14");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Traducciones al chino (descripcion/ejemplo) — 2026-07-20
    //  Se agregan aparte, sin tocar las 137 entradas de arriba. Los terminos
    //  clave IEC 61850 (LN/CDC/FC, nombres de nodo, referencias normativas)
    //  quedan sin traducir dentro del texto chino.
    // ─────────────────────────────────────────────────────────────────────────
    private static void zh(String key, String descZh) {
        Entry e = DICT.get(key);
        if (e != null) e.descriptionZh = descZh;
    }
    private static void zh(String key, String descZh, String exampleZh) {
        Entry e = DICT.get(key);
        if (e != null) { e.descriptionZh = descZh; e.exampleZh = exampleZh; }
    }
    private static void pt(String key, String descPt) {
        Entry e = DICT.get(key);
        if (e != null) e.descriptionPt = descPt;
    }
    private static void pt(String key, String descPt, String examplePt) {
        Entry e = DICT.get(key);
        if (e != null) { e.descriptionPt = descPt; e.examplePt = examplePt; }
    }
    private static void en(String key, String descEn) {
        Entry e = DICT.get(key);
        if (e != null) e.descriptionEn = descEn;
    }
    private static void en(String key, String descEn, String exampleEn) {
        Entry e = DICT.get(key);
        if (e != null) { e.descriptionEn = descEn; e.exampleEn = exampleEn; }
    }
    static {
        zh("LLN0", "根逻辑设备节点。包含报告控制块 (RCB)、GOOSE 控制块 (GoCB)、数据集和 LD 的整体配置。",
            "LLN0.Mod (运行模式)，LLN0.NamPlt (铭牌)");
        zh("LPHD", "代表实际物理设备 (IED)。包含清单信息 (厂商、型号、固件版本)、健康状态和通信状态。",
            "LPHD1.NamPlt (厂商/型号)，LPHD1.PhyHealth (硬件状态)");

        zh("CSWI", "控制断路器或隔离开关的操作 (打开/关闭)。接收 SBO (Select-Before-Operate) 命令或直接命令，并与 CILO 交互以确认联锁允许该操作。",
            "CSWI1.Pos (位置)，CSWI1.Auto (自动模式)");
        zh("CILO", "实现联锁逻辑。在授权操作前评估安全条件 (其他开关的位置、电压是否存在等)。对 CSWI 的命令进行阻塞或使能。",
            "CILO1.EnaOpn (允许分闸)，CILO1.EnaCls (允许合闸)");
        zh("CALH", "处理并优先排序过程和系统告警。可对告警进行分组、过滤和升级，并保留事件历史记录。");
        zh("CCGR", "控制变压器或其他设备 (泵、风扇) 的冷却系统。根据温度激活各级冷却。");
        zh("CPOW", "检测区域间功率振荡，可在振荡期间闭锁距离保护以避免误动作。");
        zh("CSYN", "在合闸前检查同期条件 (电压差、频率差和相角差) 是否满足要求，以连接两个电力系统。");

        zh("XCBR", "代表实际的断路器。报告其真实位置 (Pos: 中间/分闸/合闸/故障)、操作计数器、分合闸时间及机构状态。",
            "XCBR1.Pos [ST] = on/off, XCBR1.OpCnt (操作计数器)");
        zh("XSWI", "代表不具备开断故障电流能力的开断设备: 隔离开关、接地刀闸、旁路开关等。与 XCBR 类似但没有灭弧能力。",
            "XSWI1.Pos [ST], XSWI1.Loc (就地操作)");

        zh("PDIS", "测量从继电器看到的阻抗，如果落入预定义区域 (Z1, Z2, Z3…) 内则动作。是输电线路的主要保护。",
            "PDIS1.Op (动作), PDIS1.Str (启动), PDIS1.Z1 (第一段)");
        zh("PDIF", "比较被保护元件 (变压器、发电机、母线、电缆) 的进出电流。当差值超过门槛时动作，表示内部故障。",
            "PDIF1.Op, PDIF1.Str, PDIF1.RsDif (差动启动电流)");
        zh("PTOC", "具有反时限或定时限特性的过流保护。当电流超过启动整定值 (Str) 并持续曲线计算的时间后跳闸。",
            "PTOC1.Str (启动), PTOC1.Op (动作/跳闸), PTOC1.DirMod (方向)");
        zh("PIOC", "瞬时动作 (无故意延时) 的过流保护。用于高电流故障，通常对应电磁继电器中的 50 段。",
            "PIOC1.Op (动作), PIOC1.Str (启动)");
        zh("PTOV", "当电压超过整定水平并持续曲线或固定延时所定义的时间时动作。用于保护对过电压敏感的设备。");
        zh("PTUV", "当电压低于整定水平时动作。用于检测失压、长时间电压跌落或电网故障。");
        zh("PTRC", "在向断路器发出命令前，集中并处理多个保护功能的跳闸信号。支持与/或逻辑、闭锁及跳闸时序控制。",
            "PTRC1.Tr (总跳闸), PTRC1.Op1...Op3 (各独立跳闸)");
        zh("PSCH", "将多个保护功能作为完整方案进行协调 (线路差动、跳闸传输、区段加速等)。");
        zh("PHAR", "在变压器投入 (励磁涌流) 期间，通过检测高二次谐波分量来闭锁或限制差动保护。");
        zh("PHIZ", "检测高阻抗接地故障 (如树木倒落、潮湿路面) — 这类故障产生的电流不足以被常规继电器检测到。");
        zh("PTEF", "具有延时特性的接地故障保护。检测表示接地故障的零序或残余电流。");
        zh("PSDE", "用于中性点不接地或经消弧线圈接地系统的灵敏方向性接地故障保护。可检测非常微小的故障电流。");
        zh("PRTR", "变压器绕组的接地故障差动保护。比较中性点电流与各相电流之和。");
        zh("PDOP", "当某方向的有功功率超过整定值时动作。适用于发电机及联络线。");
        zh("PUPF", "当功率因数低于整定值时动作。保护发电机免于运行在过度吸收无功的区域。");
        zh("PVOC", "启动整定值随电压自动变化的时限过流保护，适用于故障时电压跌落的发电机。");
        zh("PZSU", "检测旋转设备转速低于额定值或完全停转，防止起动不完全或失步。");
        zh("PVPH", "通过检测超过整定值的 V/Hz 比值，保护变压器和发电机免受过励磁 (过度励磁) 损害。");
        zh("PTOF", "当系统频率超过整定值时动作。用于发电机及频率控制方案。");
        zh("PTTR", "对设备 (变压器、电缆、电机) 的发热进行建模，在温度损坏绝缘之前动作。");

        zh("RBRF", "若断路器在收到跳闸命令后未能分闸，则对相邻断路器发出后备跳闸以隔离故障。",
            "RBRF1.Op (后备动作), RBRF1.StrBF (断路器失灵启动)");
        zh("RREC", "保护跳闸后，自动尝试恢复线路供电，可进行 1、2 或 3 次重合闸 (单相或三相)，重合闸时间可配置。",
            "RREC1.Op (重合成功), RREC1.Str (启动), RREC1.Auto (已使能)");
        zh("RSYN", "在授权合闸前，检查断路器两侧的电压差、频率差和相角差是否满足要求。");
        zh("RPSB", "在功率振荡期间闭锁距离保护，避免误动作。");
        zh("RDRE", "在事件发生前后采集电压和电流波形。以 COMTRADE 格式存储记录以供故障后分析。",
            "RDRE1.Op (录波触发), RDRE1.ChNum (通道数)");
        zh("RFLO", "利用故障发生时刻的电压和电流测量值，计算输电线路上的故障距离。");

        zh("MMXU", "主要测量节点: 相电压及相间电压、相电流、三相有功/无功/视在功率、功率因数及频率。是计量表和测量继电器中最常用的节点。",
            "MMXU1.PhV (WYE 相电压), MMXU1.A (WYE 相电流), MMXU1.TotW (总有功功率), MMXU1.Hz (频率)");
        zh("MMXN", "测量不按相分解的电气量: 中性点电流、零序电压等。");
        zh("MHAI", "按相测量电压和电流的谐波及间谐波含量 (最高至第 50 次谐波)。包含 THD 及畸变系数。对电能质量分析至关重要。",
            "MHAI1.ThdA (电流 THD), MHAI1.HKf (主导谐波次数)");
        zh("MHAN", "与 MHAI 类似，但用于不按相分解的量 (中性点、零序)。");
        zh("MSQI", "计算电压和电流的对称分量 (正序、负序、零序) 及不平衡度指标。",
            "MSQI1.SeqA (电流序分量), MSQI1.SeqV (电压序分量)");
        zh("MMTR", "累积有功、无功和视在能量 (输入及输出)。计数器类型为 BCR (Binary Counter Reading), FC=ST。",
            "MMTR1.TotWh (有功能量 Wh), MMTR1.TotVArh, MMTR1.TotVAh");
        zh("MSTA", "计算需量值 (时段内的平均值): 平均功率、最大需量、负荷率等。");
        zh("MDIF", "监测两点之间的电流差异，不具备跳闸功能。用于持续的完整性监视。");
        zh("MFLK", "按 IEC 61000-4-15 测量电压闪变: Pst (短期，10 分钟) 和 Plt (长期，2 小时)。");

        zh("ATCC", "控制变压器的分接开关 (tap changer)，将二次侧电压调节在设定的范围内。",
            "ATCC1.TapPos (当前分接位置), ATCC1.Auto (自动模式)");
        zh("AVCO", "协调多台带分接开关的变压器或电容器组，以调节母线电压。");
        zh("ARCO", "调节注入或吸收的无功功率 (电容器组、SVC、发电机)，以维持电压和功率因数。");

        zh("GAPC", "用于其他 LN 未覆盖的过程控制逻辑的通用节点。具有通用的数字/模拟输入输出。");
        zh("GGIO", "提供 IED 通用的数字/模拟输入输出。广泛用于状态信号、告警和各类命令。",
            "GGIO1.Ind1...Ind16 (数字指示), GGIO1.AnIn1 (模拟输入)");
        zh("GSAL", "生成与 IED 网络安全相关的告警: 未授权访问、配置完整性等。");

        zh("YTRF", "对电力变压器建模: 变比、接线组别、温度、油位、溶解气体 (DGA) 等。",
            "YTRF1.NamPlt (铭牌), YTRF1.TmpHot (热点温度)");
        zh("YLTC", "代表有载分接开关 (On-Load Tap Changer)。报告当前位置、限值及操作计数器。",
            "YLTC1.TapPos (位置), YLTC1.TapChg (分接变化)");
        zh("YEFN", "控制消弧线圈 (谐振接地电抗器)，以补偿中性点不接地/补偿系统中的电容电流。");
        zh("YPSH", "代表并联电容器组、并联电抗器或其他用于无功补偿的并联元件。");

        zh("ZLIN", "对输电线路建模: 长度、阻抗、电容、额定电流。作为故障定位器的参考数据。");
        zh("ZGEN", "对发电机建模: 额定功率、额定功率因数、电压、转速。可包含能力曲线。");
        zh("ZMOT", "对电动机建模: 额定电流、功率因数、绕组温度、启动次数。");
        zh("ZCAP", "代表用于并联无功补偿的电容器组。包含级数、额定无功及各级状态。");
        zh("ZBAT", "监测 IED 或变电站的后备电池或 UPS: 电压、电流、荷电状态、温度。");
        zh("ZSAR", "对氧化锌避雷器建模。可包含放电计数器、泄漏电流及温度。");

        zh("TCTR", "对电流互感器 (CT) 建模: 变比、精度等级、额定负载、饱和系数。精确变电站建模所必需。",
            "TCTR1.Rat (变比), TCTR1.A (测量的二次电流)");
        zh("TVTR", "对电压互感器 (VT/PT) 建模: 变比、精度等级、额定负载及电压系数。",
            "TVTR1.Rat (变比), TVTR1.Vol (测量的二次电压)");

        zh("STMP", "监测设备各点温度 (油温、绕组、环境、热点)，并对过温进行告警和跳闸。");
        zh("SCBR", "监测断路器的机械和电气状态: SF6 压力、弹簧压力、油位、触头磨损。");
        zh("SIMG", "监测灭弧室的 SF6 气体: 压力、密度、温度及低气压告警。");
        zh("SOPM", "监测运行参数: 跳闸计数器、累计开断电流、空载操作次数。");

        zh("ST", "过程实时状态值。是读取最频繁的数据: 开关位置、告警、品质及保护指示。客户端可访问，并通过 RCB 自动上报。",
            "XCBR1.Pos [ST], MMXU1.TotW [ST]");
        zh("MX", "过程模拟值: 电压、电流、功率、频率等。周期性更新或在显著变化时更新。通过 RCB 上报或由客户端轮询读取。",
            "MMXU1.PhV [MX], MMXU1.A [MX], MMXU1.Hz [MX]");
        zh("CO", "用于控制序列的属性: 控制值 (ctlVal)、来源 (origin)、序列号 (ctlNum)、操作时间 (operTm)。客户端写入这些属性以向 IED 发送命令。",
            "XCBR1.Pos.ctlVal [CO] — 发送 open/close 命令");
        zh("CF", "逻辑节点的配置参数: 死区、完整性周期、时间常数。通常在投运期间写入。",
            "MMXU1.PhV.db [CF] — 变化报告的死区");
        zh("DC", "LN 的铭牌数据: 厂商、型号、软件版本、序列号。只读。典型访问方式: F=DC 或 F=LPL。",
            "XCBR1.NamPlt [DC]");
        zh("SG", "当前激活的定值组的数值。客户端可读取当前生效的整定值，但不能在此修改 (需使用 SE 进行编辑)。",
            "PTOC1.StrVal [SG] — 当前激活定值组的启动门槛");
        zh("SE", "正在编辑的定值组。客户端可在通过 ConfSG 命令激活前修改这些数值。");
        zh("SP", "客户端可直接修改 (无需预先选择) 的设定点。与 SG 不同，这是一个运行值，而非定值组。");
        zh("EX", "厂商或应用相关的扩展定义属性。不属于标准规范模型的一部分。");
        zh("OR", "指示已收到操作命令。内部用于在 SBO (Select-Before-Operate) 序列中跟踪命令。");
        zh("BL", "闭锁某属性的数值，阻止其更新。用于维护测试 (blkEna=true)。即使过程发生变化，被冻结的值仍保持不变。");

        zh("SPS", "过程状态的布尔位: 真 (TRUE) 或假 (FALSE)。包含: stVal (数值)、q (品质) 和 t (时间戳)。例如: 断路器闭锁、告警信号、模式使能。",
            "BlkOpn, BlkCls, Loc, Auto, EnaOpn, EnaCls");
        zh("DPS", "具有四种取值的过程状态: 中间 (00)、分闸 (01)、合闸 (10)、故障 (11)。是断路器和隔离开关位置的标准 CDC。",
            "Pos (XCBR/XSWI 的位置) — 操作过程中为中间状态");
        zh("INS", "带品质和时间戳的过程状态整数值。用于分接头位置、操作计数器等。",
            "TapPos (分接开关位置), OpCnt (计数器)");
        zh("ENS", "枚举型状态值: 运行模式 (on/blocked/test/off)、健康状态 (OK/Warning/Alarm)、LN 的行为。",
            "Mod (模式: on/blocked/test/test-blocked/off), Beh, Health");
        zh("ACT", "表示某保护功能已动作。包含: 总信号 (general)、分相 (phsA/phsB/phsC) 及方向。",
            "Op (跳闸动作), Str (启动)");
        zh("ACD", "在 ACT 基础上扩展方向信息: forward, backward, both。用于方向性保护 (距离保护、接地方向保护)。");
        zh("SEC", "带品质和时间戳的安全事件计数器。用于 GSAL 节点。");
        zh("BCR", "累加型二进制计数器读数: actVal (累计值 INT64)、frVal (小数部分)、q (品质) 和 t (读取时间戳)。FC=ST。除以 1000 可将 Wh 转换为 kWh。",
            "TotWh.actVal [ST] / 1000 = kWh");
        zh("MV", "标量模拟值: mag.f (浮点) 或 mag.i (整数)，加上 q (品质) 和 t (时间戳)。是无相位关联量最简单的表示方式。",
            "TotW, TotVAr, Hz, PF → 都是 MV");
        zh("CMV", "相量值: 幅值 (mag.f) 和相角 (ang.f)。用于分别表示电压和电流的相量。",
            "SEQ 的分量: c1, c2, c0 都是 CMV");
        zh("SAV", "电压或电流的瞬时采样值，主要用于采样值 (Sampled Values, SV)。包含 instVal (瞬时值)。");
        zh("WYE", "汇集三个相量 (phsA, phsB, phsC) 及中性点 (neut)，每个都是 CMV 类型。是星形接线中相电压和线电流的标准 CDC。",
            "PhV (相电压), A (线电流), W, VAr, VA, PF");
        zh("DEL", "汇集三个相间相量 (phsAB, phsBC, phsCA)，每个都是 CMV。用于三角形接线中的线电压 (相间电压)。",
            "PPV (相间电压)");
        zh("SEQ", "对称分解: c1 (正序)、c2 (负序)、c0 (零序)，每个分量都是带幅值和相角的 CMV。",
            "SeqV.c1 (正序电压), SeqA.c2 (负序电流)");
        zh("SPC", "布尔控制点: 客户端发送 ctlVal=TRUE/FALSE。过程通过改变 stVal 作出响应。用于简单数字输出的 CDC。");
        zh("DPC", "位置控制: 客户端命令 CLOSE (01) 或 OPEN (10)。响应包含中间状态。是断路器/隔离开关命令的标准 CDC。",
            "XCBR/XSWI 的 Pos — stVal 为 DPS 类型，ctlVal 为 SPC 类型 (open/close)");
        zh("INC", "允许客户端写入整数值。用于手动分接开关操作、定值组编号选择等。",
            "带控制的 TapPos, SetGrp (定值组选择)");
        zh("ENC", "通过枚举方式控制: 客户端发送模型中定义的某个值。典型用于 Mod (LN 的模式)。",
            "Mod.ctlVal = on/blocked/test/test-blocked/off");
        zh("BSC", "分步控制位置: '升' (higher) 或 '降' (lower) 命令。用于按级控制的分接开关。");
        zh("APC", "允许客户端写入模拟量 (浮点) 值。用于调节器的设定点: 目标电压、功率等。");
        zh("BAC", "通过二进制步进 (升/降) 控制的模拟设定点。结合了 APC 和 BSC。");
        zh("LPL", "LN 或物理设备的标识数据: 厂商 (vendor)、型号 (swRev, hwRev)、序列号 (serNum)、描述 (d)、位置 (loc)、IED 类型 (model)。",
            "LLN0 和 LPHD 的 NamPlt");
        zh("DPL", "物理设备或现场部件的铭牌数据: 厂商、序列号、类型、额定电压/电流。");

        zh("Pos", "开断设备的位置 (DPS): 中间/分闸/合闸/故障。适用于 XCBR 和 XSWI。当 FC=CO 时也是控制 DO (ctlVal)。",
            "Pos [ST] = on → 合闸; off → 分闸");
        zh("NamPlt", "逻辑节点或设备的标识铭牌 (CDC LPL)。包含 vendor, swRev, hwRev, serNum, model。FC=DC。");
        zh("Health", "逻辑节点的健康状态: OK (1), Warning (2), Alarm (3)。CDC ENS。反映 LN 是否正常工作。");
        zh("Mod", "LN 的运行模式 (CDC ENC): on, blocked, test, test-blocked, off。客户端可通过 ctlVal 修改。整定用 FC=CF，命令用 FC=CO。");
        zh("Beh", "逻辑节点的实际行为 (CDC ENS)。综合考虑 Mod 及可能的外部闭锁后反映的实际状态。");
        zh("Loc", "指示设备处于就地操作模式 (TRUE) 或远方 (FALSE)。CDC SPS。处于就地模式时，远方命令可能被拒绝。",
            "Loc [ST] = TRUE → 只接受来自就地面板的操作");
        zh("BlkOpn", "闭锁断路器分闸 (SPS)。为 TRUE 时，CILO 联锁将拒绝分闸命令。");
        zh("BlkCls", "闭锁断路器合闸 (SPS)。为 TRUE 时，CILO 联锁将拒绝合闸命令。");
        zh("EnaOpn", "来自 CILO 的分闸使能信号。TRUE = 允许分闸。");
        zh("EnaCls", "来自 CILO 的合闸使能信号。TRUE = 允许合闸。");
        zh("Auto", "指示设备运行在自动模式 (TRUE) 还是手动模式 (FALSE)。SPS。");
        zh("Op", "指示保护功能已动作 (已跳闸)。CDC ACT。包含 general, phsA, phsB, phsC 和 neut。",
            "PTOC1.Op.general [ST] = TRUE → 保护已跳闸");
        zh("Str", "指示保护功能已启动 (超过门槛，开始计时)。CDC ACT/ACD。若条件持续则先于 Op 出现。",
            "PDIS1.Str.general [ST] = TRUE → 阻抗落入区内");
        zh("PhV", "相对中性点的相电压 (CDC WYE)。phsA, phsB, phsC, neut。每个分量都是带幅值 (mag.f，单位 V) 和相角 (ang.f，单位 °) 的 CMV。",
            "MMXU1.PhV.phsA.mag.f [MX] = 127000 V (二次侧 127 kV)");
        zh("PPV", "相间电压 (CDC DEL): phsAB, phsBC, phsCA。CMV 类型。在 220 kV 系统中，PPV ≈ PhV × √3。");
        zh("A", "三相线电流 (CDC WYE): phsA, phsB, phsC, neut。CMV 类型，幅值单位为 A，相角单位为 °。",
            "MMXU1.A.phsA.mag.f [MX] = A 相电流");
        zh("W", "按相划分的三相有功功率 (CDC WYE)。单位 MW。phsA + phsB + phsC ≈ TotW。");
        zh("VAr", "按相划分的三相无功功率 (CDC WYE)。单位 MVAr。正值 = 感性 (消耗)，负值 = 容性 (发出)。");
        zh("VA", "按相划分的三相视在功率 (CDC WYE)。单位 MVA。VA = √(W² + VAr²)。");
        zh("TotW", "三相系统的总有功功率 (CDC MV)。单位 W。为三相之和的标量结果。",
            "MMXU1.TotW.mag.f [MX] = W (除以 1e6 得到 MW)");
        zh("TotVAr", "三相系统的总无功功率 (CDC MV)。单位 VAr。");
        zh("TotVA", "三相系统的总视在功率 (CDC MV)。单位 VA。");
        zh("TotPF", "总功率因数 (CDC MV)。无量纲，范围 [-1, +1]。PF = W / VA。正值表示感性。");
        zh("Hz", "电力系统频率 (CDC MV)。单位 Hz。额定值: 50 Hz (欧洲/南美) 或 60 Hz (北美)。",
            "MMXU1.Hz.mag.f [MX] = 50.02 Hz");
        zh("PF", "按相划分的功率因数 (CDC WYE)。phsA, phsB, phsC。作为 TotPF 的分相补充。");
        zh("TotWh", "累积有功能量 (CDC BCR)。actVal 单位为 Wh (INT64)。FC=ST。除以 1000 得到 kWh。",
            "MMTR1.TotWh.actVal [ST] / 1000 = 输入的 kWh");
        zh("TotVArh", "累积无功能量 (CDC BCR)。actVal 单位为 VArh。");
        zh("TotVAh", "累积视在能量 (CDC BCR)。actVal 单位为 VAh。");
        zh("SeqA", "电流的对称分量 (CDC SEQ): c1 (正序)、c2 (负序)、c0 (零序)。带幅值和相角的 CMV。",
            "MSQI1.SeqA.c2.mag.f → 负序电流 (不平衡)");
        zh("SeqV", "电压的对称分量 (CDC SEQ): c1, c2, c0。");
        zh("ThdA", "电流的总谐波畸变率 (CDC WYE 或 MV)。以相对基波的百分比表示。典型限值: 5% (IEEE 519)。",
            "MHAI1.ThdA.phsA.mag.f [MX] = 8.5 (%)");
        zh("ThdPhV", "相电压的总谐波畸变率 (CDC WYE)。典型限值: 5% (IEEE 519) 或 8% (IEC 61000-2-2)。");
        zh("TapPos", "分接开关当前位置 (CDC INS 或 INC)。整数值: 0 = 中间位置，正值 = 升压。",
            "YLTC1.TapPos.stVal [ST] = 3 (中心位以上第三挡)");
        zh("OpCnt", "累计分合闸操作计数器 (CDC INS)。用于维护计划。");
        zh("OpDlTmms", "断路器分闸时间，单位毫秒 (CDC MV)。触头磨损时会增加。");
        zh("CBOpCap", "断路器剩余开断能力百分比 (CDC MV)。每次带负荷操作后会降低。");

        zh("stVal", "过程数据的当前值: 布尔型 (SPS)、整数 (DPS/INS)、浮点 (MV) 或枚举型。是 CDC 的主要读取属性。",
            "Pos.stVal = on (合闸), TotW.mag.f = 125000 (W)");
        zh("q", "数值的品质指示: validity (good/invalid/reserved/questionable)、overflow、outOfRange、badReference、oscillatory、failure、oldData、inconsistent、inaccurate、source (process/substituted)、test、operatorBlocked。",
            "q.validity = good → 数值可信");
        zh("t", "数值最后一次变化的时间戳。分辨率为 1 ms，包含时钟品质信息 (clockNotSynchronized, clockFailure 等)。",
            "t = 2026-04-23T14:30:00.123Z");
        zh("mag", "CMV (复数测量值) 的幅值分量。包含 f (浮点) 或 i (整数)。",
            "PhV.phsA.mag.f = 63508.5 (V)");
        zh("ang", "CMV 的相角分量，单位为度 (°)。按惯例以 A 相电压相量为参考。",
            "A.phsA.ang.f = -32.5 (°) → 电流相对于电压的相位差");
        zh("f", "浮点属性 (FLOAT32)。是相量 (CMV) 中 mag 或 ang 的子属性，或 SAV 中 instVal 的子属性。");
        zh("i", "整数属性 (INT32)。是整数量 (如计数器、分接位置) 中 f 的替代类型。");
        zh("ctlVal", "客户端发送以控制过程的数值: 布尔型 (SPC/DPC)、整数 (INC) 或浮点 (APC)。FC=CO。是控制序列的核心。",
            "Pos.ctlVal = true → 合闸命令");
        zh("origin", "指示命令的发出者: orCat (类别: not-supported/bay-control/station-control/remote-control/automatic-bay/...) 及 orIdent (标识)。",
            "origin.orCat = remote-control → 来自 SCADA 的命令");
        zh("ctlNum", "控制的序列号 (0-255)。服务器在响应中返回，供客户端识别该响应对应的命令。");
        zh("operTm", "计划执行控制的时间 (定时控制)。若为零，则控制立即执行。");
        zh("actVal", "BCR 计数器的累计值 (INT64)。对于能量: actVal 单位为 Wh，除以 1000 得到 kWh。",
            "TotWh.actVal = 12345678 Wh = 12345.678 kWh");
        zh("frVal", "BCR 计数器的小数部分，用于提高分辨率。actVal + frVal/2^32 = 计数器实际值。");
        zh("vendor", "IED 或部件的厂商名称。NamPlt (LPL) 的属性。FC=DC。",
            "NamPlt.vendor = 'Siemens AG'");
        zh("swRev", "IED 固件/软件版本。NamPlt 的属性。FC=DC。");
        zh("hwRev", "IED 硬件版本。NamPlt 的属性。FC=DC。");
        zh("serNum", "设备唯一序列号。NamPlt 的属性。FC=DC。");
        zh("model", "设备型号标识。NamPlt 的属性。FC=DC。",
            "NamPlt.model = 'SIPROTEC 5 7SL86'");
        zh("d", "元素的描述性文本字符串 (ASCII)。FC=DC。用于 LN 或 DO 的内部标注。");
        zh("dU", "LN 或 DO 的 Unicode 描述。支持非 ASCII 字符。FC=DC。");
        zh("db", "基于数值变化触发 (dchg) 的死区。以工程单位量程指定。FC=CF。",
            "PhV.db = 100 → 电压变化超过 100 V 时上报");
        zh("zeroDb", "接近零值时使用的死区。FC=CF。");
        zh("smpRate", "SAV (采样值) 的采样率。FC=CF。典型值: 每个电网周期 80 或 256 个采样点。");
        zh("phsA", "WYE 或 DEL 的子属性，包含 A 相相量 (CMV)。访问方式: PhV.phsA.mag.f");
        zh("phsB", "WYE 的子属性，包含 B 相相量 (CMV)。");
        zh("phsC", "WYE 的子属性，包含 C 相相量 (CMV)。");
        zh("neut", "WYE 的子属性，包含中性点相量 (CMV)。");
        zh("phsAB", "CDC DEL 中的 A 相 – B 相电压。");
        zh("phsBC", "CDC DEL 中的 B 相 – C 相电压。");
        zh("phsCA", "CDC DEL 中的 C 相 – A 相电压。");
        zh("c1", "CDC SEQ 中的正序对称分量 (CMV)。",
            "SeqA.c1.mag.f → 电流正序分量的幅值");
        zh("c2", "CDC SEQ 中的负序对称分量 (CMV)。表示相间负荷不平衡或故障。");
        zh("c0", "CDC SEQ 中的零序对称分量 (CMV)。表示接地故障电流。");
        zh("instVal", "SAV (采样值) 中的瞬时采样值。");
        zh("units", "数值的工程单位: SIUnit (W, V, A, Hz, °C...) 及 multiplier (kilo, mega, milli 等)。FC=CF。",
            "units.SIUnit = W, units.multiplier = k → kW");
        zh("general", "ACT/ACD 的属性，指示任一相发生动作。general = phsA OR phsB OR phsC。",
            "Str.general = TRUE → 某相保护启动");

        zh("FCDA", "Functional Constrained Data Attribute — GOOSE 或报告数据集中的元素。带 FC 引用模型中的特定属性: ldInst/prefixLNClass.DO.DA [FC]。");
        zh("DSET", "对模型属性 (FCDA) 的引用集合。是通过 GOOSE 发布或包含在报告 (RCB) 中的'变量列表'。定义在每个 LD 的 LLN0 中。");
        zh("GOCB", "GOOSE 消息的参数: GoID, AppID, destMAC, confRev, dataset。当数据集发生变化时由 IED 在以太网组播中发布。");
        zh("URCB", "无缓冲报告: 服务器立即发送报告。若客户端未连接，事件将丢失。同一时刻只能有一个客户端订阅某个 URCB。");
        zh("BRCB", "带缓冲报告: 即使客户端断开连接，服务器也会存储事件。重新连接后，客户端会收到所有待处理的报告。对关键应用至关重要。");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Traducciones al portugues (descripcion/ejemplo) — 2026-07-20
    //  Mismo mecanismo que ZH: no toca las entradas originales. Terminos clave
    //  IEC 61850 se dejan sin traducir dentro del texto portugues.
    // ─────────────────────────────────────────────────────────────────────────
    static {
        pt("LLN0", "Nó raiz do Dispositivo Lógico. Contém os blocos de controle de relatórios (RCB), blocos de controle GOOSE (GoCB), datasets e a configuração geral do LD.",
            "LLN0.Mod (modo de operação), LLN0.NamPlt (placa de identificação)");
        pt("LPHD", "Representa o dispositivo físico real (IED). Contém informações de inventário (fabricante, modelo, versão de firmware), estado de saúde e comunicação.",
            "LPHD1.NamPlt (fabricante/modelo), LPHD1.PhyHealth (estado do hardware)");

        pt("CSWI", "Controla a operação (abertura/fechamento) de um disjuntor ou seccionadora. Recebe comandos SBO (Select-Before-Operate) ou diretos e interage com CILO para verificar se o intertravamento permite a manobra.",
            "CSWI1.Pos (posição), CSWI1.Auto (modo automático)");
        pt("CILO", "Implementa a lógica de intertravamento. Avalia as condições de segurança (posição de outros disjuntores, presença de tensão, etc.) antes de autorizar uma manobra. Bloqueia ou habilita o comando sobre CSWI.",
            "CILO1.EnaOpn (habilitar abertura), CILO1.EnaCls (habilitar fechamento)");
        pt("CALH", "Processa e prioriza alarmes de processo e sistema. Pode agrupar, filtrar e escalar alarmes, e mantém registro histórico de eventos.");
        pt("CCGR", "Controla os sistemas de refrigeração de transformadores ou outros equipamentos (bombas, ventiladores). Ativa estágios conforme a temperatura.");
        pt("CPOW", "Detecta oscilações de potência inter-área e pode bloquear proteções de distância durante a oscilação para evitar disparos incorretos.");
        pt("CSYN", "Verifica se as condições de sincronismo (diferença de tensão, frequência e ângulo de fase) são aceitáveis antes de fechar um disjuntor e conectar dois sistemas elétricos.");

        pt("XCBR", "Representa o disjuntor de potência físico. Reporta sua posição real (Pos: intermediário/aberto/fechado/defeito), contadores de operações, tempo de abertura/fechamento e estado do acionamento.",
            "XCBR1.Pos [ST] = on/off, XCBR1.OpCnt (contador de operações)");
        pt("XSWI", "Representa um dispositivo de corte sem capacidade de interrupção de corrente de falta: seccionadora, chave de aterramento, by-pass, etc. Similar ao XCBR mas sem arco de interrupção.",
            "XSWI1.Pos [ST], XSWI1.Loc (operação local)");

        pt("PDIS", "Mede a impedância vista pelo relé e atua se cair dentro de uma zona predefinida (Z1, Z2, Z3…). Principal proteção de linhas de transmissão.",
            "PDIS1.Op (operação), PDIS1.Str (partida), PDIS1.Z1 (zona 1)");
        pt("PDIF", "Compara as correntes de entrada e saída de um elemento protegido (transformador, gerador, barra, cabo). Atua quando a diferença ultrapassa o limiar, indicando falta interna.",
            "PDIF1.Op, PDIF1.Str, PDIF1.RsDif (corrente diferencial de partida)");
        pt("PTOC", "Proteção de sobrecorrente com característica de tempo inverso ou definido. Dispara quando a corrente ultrapassa o ajuste de partida (Str) durante o tempo calculado pela curva.",
            "PTOC1.Str (partida), PTOC1.Op (operação/disparo), PTOC1.DirMod (direcional)");
        pt("PIOC", "Proteção de sobrecorrente de atuação instantânea (sem retardo intencional). Cobre faltas de alta corrente, normalmente o 50 dos relés eletromecânicos.",
            "PIOC1.Op (operação), PIOC1.Str (partida)");
        pt("PTOV", "Atua quando a tensão ultrapassa o nível de ajuste durante o tempo definido pela curva ou retardo fixo. Protege equipamentos sensíveis à sobretensão.");
        pt("PTUV", "Atua quando a tensão cai abaixo do nível de ajuste. Detecta perda de tensão, afundamentos prolongados ou falha da rede.");
        pt("PTRC", "Centraliza e condiciona os disparos de múltiplas funções de proteção antes de enviar a ordem ao disjuntor. Permite lógicas AND/OR, bloqueios e sequenciamento de disparos.",
            "PTRC1.Tr (disparo geral), PTRC1.Op1...Op3 (disparos individuais)");
        pt("PSCH", "Coordena múltiplas funções de proteção como esquema completo (diferencial de linha, transferência de disparo, aceleração de zona, etc.).");
        pt("PHAR", "Bloqueia ou restringe a proteção diferencial de transformador durante a energização (inrush) detectando a alta componente de 2º harmônico.");
        pt("PHIZ", "Detecta faltas de alta impedância à terra (árvore caída, pavimento úmido) que não produzem corrente suficiente para os relés convencionais.");
        pt("PTEF", "Proteção de falta à terra com característica temporizada. Detecta correntes de sequência zero ou residuais que indicam falta à terra.");
        pt("PSDE", "Proteção de falta à terra sensível e direcional para redes com neutro isolado ou compensado (Petersen). Detecta correntes de falta muito pequenas.");
        pt("PRTR", "Proteção diferencial de falta à terra para o enrolamento de um transformador. Compara a corrente do neutro com a soma das correntes de fase.");
        pt("PDOP", "Atua quando o fluxo de potência ativa em uma direção ultrapassa o ajuste. Útil para geradores e interconexões.");
        pt("PUPF", "Atua quando o fator de potência cai abaixo do ajuste. Protege geradores contra operação em zona de absorção reativa excessiva.");
        pt("PVOC", "Sobrecorrente temporizada cujo ajuste de partida varia automaticamente em função da tensão, útil para geradores com redução de tensão em falta.");
        pt("PZSU", "Detecta que uma máquina rotativa está abaixo da velocidade nominal ou totalmente parada, protegendo contra partida incompleta ou perda de passo.");
        pt("PVPH", "Protege transformadores e geradores contra sobre-excitação (excitação excessiva) detectando uma relação V/Hz superior ao ajuste.");
        pt("PTOF", "Atua quando a frequência do sistema ultrapassa o ajuste. Usado em geradores e esquemas de controle de frequência.");
        pt("PTTR", "Modela o aquecimento do equipamento (transformador, cabo, motor) e atua antes que a temperatura danifique o isolamento.");

        pt("RBRF", "Se o disjuntor não abrir após receber a ordem de disparo, inicia um disparo de retaguarda sobre os disjuntores adjacentes para isolar a falta.",
            "RBRF1.Op (operação de retaguarda), RBRF1.StrBF (partida falha de disjuntor)");
        pt("RREC", "Após um disparo de proteção, tenta restabelecer a linha automaticamente com 1, 2 ou 3 tentativas de religamento (monofásico ou trifásico) e tempos mortos configuráveis.",
            "RREC1.Op (religamento bem-sucedido), RREC1.Str (partida), RREC1.Auto (habilitado)");
        pt("RSYN", "Verifica se a diferença de tensão, frequência e ângulo entre os dois lados de um disjuntor é aceitável antes de autorizar o fechamento.");
        pt("RPSB", "Bloqueia a proteção de distância durante oscilações de potência para evitar disparos intempestivos.");
        pt("RDRE", "Captura formas de onda de tensão e corrente antes e depois de um evento. Armazena registros em formato COMTRADE para análise pós-falta.",
            "RDRE1.Op (disparo do registro), RDRE1.ChNum (número de canais)");
        pt("RFLO", "Calcula a distância até a falta em uma linha de transmissão usando as medições de tensão e corrente no momento do evento.");

        pt("MMXU", "Nó de medição principal: tensões de fase e fase-fase, correntes de fase, potências ativa/reativa/aparente trifásicas, fator de potência e frequência. É o nó mais usado em medidores e relés de medição.",
            "MMXU1.PhV (tensão por fases WYE), MMXU1.A (corrente WYE), MMXU1.TotW (potência ativa total), MMXU1.Hz (frequência)");
        pt("MMXN", "Medição de grandezas elétricas que não se decompõem por fase: corrente de neutro, tensão de sequência zero, etc.");
        pt("MHAI", "Mede o conteúdo harmônico e inter-harmônico de tensões e correntes por fase (até o 50º harmônico). Inclui THD e fatores de distorção. Crucial para análise de qualidade de energia.",
            "MHAI1.ThdA (THD de corrente), MHAI1.HKf (ordem do harmônico dominante)");
        pt("MHAN", "Similar ao MHAI, mas para grandezas sem decomposição por fase (neutro, sequência zero).");
        pt("MSQI", "Calcula componentes simétricas (sequências positiva, negativa e zero) de tensões e correntes, e os índices de desequilíbrio.",
            "MSQI1.SeqA (sequência de corrente), MSQI1.SeqV (sequência de tensão)");
        pt("MMTR", "Integra energia ativa, reativa e aparente (importada e exportada). Os contadores são do tipo BCR (Binary Counter Reading), FC=ST.",
            "MMTR1.TotWh (energia ativa Wh), MMTR1.TotVArh, MMTR1.TotVAh");
        pt("MSTA", "Calcula valores de demanda (média em intervalo de tempo): potência média, demanda máxima, fator de carga, etc.");
        pt("MDIF", "Monitora a diferença de corrente entre dois pontos sem função de disparo. Usado para supervisão contínua de integridade.");
        pt("MFLK", "Mede a flutuação de tensão (flicker) conforme IEC 61000-4-15: Pst (curto prazo, 10 min) e Plt (longo prazo, 2 horas).");

        pt("ATCC", "Controla o comutador de derivações (tap changer) de um transformador para regular a tensão no lado secundário dentro de uma faixa de ajuste.",
            "ATCC1.TapPos (posição atual do tap), ATCC1.Auto (modo automático)");
        pt("AVCO", "Coordena múltiplos transformadores com tap changer ou bancos de capacitores para regular a tensão em uma barra.");
        pt("ARCO", "Regula a potência reativa injetada ou absorvida (bancos de capacitores, SVCs, geradores) para manter a tensão e o fator de potência.");

        pt("GAPC", "Nó genérico para lógica de controle de processo não coberta por outros LN. Possui entradas/saídas digitais e analógicas genéricas.");
        pt("GGIO", "Expõe entradas e saídas digitais/analógicas de propósito geral do IED. Muito usado para sinais de estado, alarmes e comandos diversos.",
            "GGIO1.Ind1...Ind16 (indicações digitais), GGIO1.AnIn1 (entrada analógica)");
        pt("GSAL", "Gera alarmes relacionados à segurança cibernética do IED: acessos não autorizados, integridade de configuração, etc.");

        pt("YTRF", "Modela o transformador de potência: relação de transformação, grupo vetorial, temperatura, nível de óleo, gás dissolvido (DGA), etc.",
            "YTRF1.NamPlt (placa), YTRF1.TmpHot (temperatura do ponto quente)");
        pt("YLTC", "Representa o comutador de derivações em carga (On-Load Tap Changer). Informa a posição atual, limites e contador de operações.",
            "YLTC1.TapPos (posição), YLTC1.TapChg (mudança de tap)");
        pt("YEFN", "Controla a bobina Petersen (reatância de aterramento ressonante) para compensar a corrente capacitiva em redes com neutro isolado/compensado.");
        pt("YPSH", "Representa um banco de capacitores, reator em derivação ou outro elemento conectado em paralelo para compensação reativa.");

        pt("ZLIN", "Modela a linha de transmissão: comprimento, impedância, capacitância, corrente nominal. Serve como referência para o localizador de faltas.");
        pt("ZGEN", "Modela o gerador elétrico: potência nominal, fator de potência nominal, tensão, velocidade. Pode incluir curva de capabilidade.");
        pt("ZMOT", "Modela um motor elétrico: corrente nominal, fator de potência, temperatura do enrolamento, número de partidas.");
        pt("ZCAP", "Representa um banco de capacitores para compensação reativa em derivação. Inclui número de estágios, reativo nominal e estado de cada estágio.");
        pt("ZBAT", "Monitora a bateria de reserva ou UPS do IED ou da subestação: tensão, corrente, estado de carga, temperatura.");
        pt("ZSAR", "Modela o para-raios de óxido de zinco. Pode incluir contador de descargas, corrente de fuga e temperatura.");

        pt("TCTR", "Modela o TC: relação de transformação, classe de precisão, carga nominal, fator de saturação. Necessário para modelos de subestação precisos.",
            "TCTR1.Rat (relação), TCTR1.A (corrente secundária medida)");
        pt("TVTR", "Modela o TP: relação de transformação, classe de precisão, carga nominal e fator de tensão.",
            "TVTR1.Rat (relação), TVTR1.Vol (tensão secundária medida)");

        pt("STMP", "Supervisiona temperaturas em pontos do equipamento (óleo, enrolamento, ambiente, ponto quente) com alarmes e disparos por sobretemperatura.");
        pt("SCBR", "Supervisiona o estado mecânico e elétrico do disjuntor: pressão de SF6, pressão de mola, nível de óleo, desgaste de contatos.");
        pt("SIMG", "Supervisiona o gás SF6 de câmaras de interrupção: pressão, densidade, temperatura e alarmes de baixo nível.");
        pt("SOPM", "Supervisiona parâmetros de operação: contador de disparos, corrente de interrupção acumulada, número de operações a vazio.");

        pt("ST", "Valores de estado do processo em tempo real. São os dados mais lidos: posição de disjuntores, alarmes, qualidade e indicações de proteção. Acessíveis pelo cliente e reportados automaticamente via RCB.",
            "XCBR1.Pos [ST], MMXU1.TotW [ST]");
        pt("MX", "Valores analógicos de processo: tensão, corrente, potência, frequência, etc. Atualizados periodicamente ou por mudança significativa. Reportados via RCB ou lidos por polling.",
            "MMXU1.PhV [MX], MMXU1.A [MX], MMXU1.Hz [MX]");
        pt("CO", "Atributos usados em sequências de controle: valor de controle (ctlVal), origem (origin), número de sequência (ctlNum), tempo de operação (operTm). O cliente escreve esses atributos para enviar comandos ao IED.",
            "XCBR1.Pos.ctlVal [CO] — envia comando open/close");
        pt("CF", "Parâmetros de configuração do nó lógico: deadband, período de integridade, constantes de tempo. Normalmente escritos durante a colocação em serviço.",
            "MMXU1.PhV.db [CF] — deadband para relatórios por mudança");
        pt("DC", "Dados de placa (nameplate) do LN: fabricante, modelo, versão de software, número de série. Somente leitura. Acesso típico: F=DC ou F=LPL.",
            "XCBR1.NamPlt [DC]");
        pt("SG", "Valores do grupo de ajustes atualmente ativo. O cliente pode ler os ajustes vigentes, mas não modificá-los aqui (usar SE para editar).",
            "PTOC1.StrVal [SG] — limiar de partida do ajuste ativo");
        pt("SE", "Acesso ao grupo de ajustes em edição. O cliente pode modificar esses valores antes de ativá-los com o comando ConfSG.");
        pt("SP", "Ponto de ajuste que o cliente pode modificar diretamente (sem seleção prévia). Diferente de SG, é um valor operacional, não um grupo de ajustes.");
        pt("EX", "Atributos de definição estendida, específicos do fabricante ou da aplicação. Não fazem parte do modelo normativo padrão.");
        pt("OR", "Indica que uma ordem de operação foi recebida. Usado internamente para rastreamento de comandos em sequências SBO (Select-Before-Operate).");
        pt("BL", "Bloqueia o valor de um atributo impedindo sua atualização. Usado em testes de manutenção (blkEna=true). O valor congelado permanece mesmo que o processo mude.");

        pt("SPS", "Bit booleano de estado do processo: VERDADEIRO ou FALSO. Contém: stVal (valor), q (qualidade) e t (timestamp). Exemplo: disjuntor intertravado, sinal de alarme, habilitação de modo.",
            "BlkOpn, BlkCls, Loc, Auto, EnaOpn, EnaCls");
        pt("DPS", "Estado de processo com quatro valores: INTERMEDIÁRIO (00), ABERTO (01), FECHADO (10), DEFEITO (11). É o CDC padrão para posição de disjuntores e seccionadoras.",
            "Pos (posição de XCBR/XSWI) — intermediário durante a manobra");
        pt("INS", "Valor inteiro de estado do processo com qualidade e timestamp. Usado para posições de tap, contadores de operações, etc.",
            "TapPos (posição do comutador de tap), OpCnt (contador)");
        pt("ENS", "Valor enumerado de estado: modo de operação (on/blocked/test/off), estado de saúde (OK/Warning/Alarm), comportamento do LN.",
            "Mod (modo: on/blocked/test/test-blocked/off), Beh, Health");
        pt("ACT", "Indica que uma função de proteção atuou. Inclui: geral (general), por fase (phsA/phsB/phsC) e direção.",
            "Op (operação de disparo), Str (partida)");
        pt("ACD", "Estende ACT com informação de direcionalidade: forward, backward, both. Usado em proteções direcionais (distância, direcional de terra).");
        pt("SEC", "Contador com qualidade e timestamp para eventos de segurança. Usado em nós GSAL.");
        pt("BCR", "Leitura de contador binário acumulador: actVal (valor acumulado INT64), frVal (valor fracionário), q (qualidade) e t (timestamp da leitura). FC=ST. Dividir por 1000 para converter Wh → kWh.",
            "TotWh.actVal [ST] / 1000 = kWh");
        pt("MV", "Valor analógico escalar: mag.f (ponto flutuante) ou mag.i (inteiro), q (qualidade) e t (timestamp). O mais simples para uma grandeza sem fase.",
            "TotW, TotVAr, Hz, PF → todos são MV");
        pt("CMV", "Valor fasorial: magnitude (mag.f) e ângulo (ang.f). Usado para representar fasores de tensão e corrente individualmente.",
            "Componentes de SEQ: c1, c2, c0 são CMV");
        pt("SAV", "Amostra instantânea de tensão ou corrente, usada principalmente em Sampled Values (SVs). Inclui instVal (valor instantâneo).");
        pt("WYE", "Agrupa três fasores de fase (phsA, phsB, phsC) e o neutro (neut), cada um do tipo CMV. É o CDC padrão para tensões de fase e correntes de linha em conexão estrela.",
            "PhV (tensões de fase), A (correntes de linha), W, VAr, VA, PF");
        pt("DEL", "Agrupa três fasores fase-fase (phsAB, phsBC, phsCA), cada um CMV. Usado para tensões de linha (fase a fase) em conexão triângulo.",
            "PPV (tensão fase-fase)");
        pt("SEQ", "Decomposição simétrica: c1 (sequência positiva), c2 (negativa), c0 (sequência zero), cada componente do tipo CMV com magnitude e ângulo.",
            "SeqV.c1 (tensão sequência positiva), SeqA.c2 (corrente negativa)");
        pt("SPC", "Ponto de controle booleano: o cliente envia ctlVal=TRUE/FALSE. O processo responde mudando stVal. CDC para saídas digitais simples.");
        pt("DPC", "Controle de posição: o cliente ordena CLOSE (01) ou OPEN (10). A resposta inclui os estados intermediários. CDC padrão para comandos a disjuntores/seccionadoras.",
            "Pos de XCBR/XSWI — stVal é DPS, ctlVal é SPC (open/close)");
        pt("INC", "Permite ao cliente escrever um valor inteiro. Usado para mudanças de tap manuais, ajuste de número de grupo de ajuste, etc.",
            "TapPos com controle, SetGrp (seleção de grupo de ajuste)");
        pt("ENC", "Controle por enumeração: o cliente envia um dos valores definidos no modelo. Típico para Mod (modo do LN).",
            "Mod.ctlVal = on/blocked/test/test-blocked/off");
        pt("BSC", "Controle de posição por passos: comandos 'subir' (higher) ou 'descer' (lower). Usado para tap changer controlado em degraus.");
        pt("APC", "Permite ao cliente escrever um valor analógico (float). Usado para set-points de reguladores: tensão alvo, potência, etc.");
        pt("BAC", "Set point analógico controlado por passos binários (subir/descer). Combina APC e BSC.");
        pt("LPL", "Dados de identificação do LN ou do equipamento físico: fabricante (vendor), modelo (swRev, hwRev), número de série (serNum), descrição (d), localização (loc), tipo de IED (model).",
            "NamPlt de LLN0 e LPHD");
        pt("DPL", "Dados de placa do dispositivo físico ou componente de campo: fabricante, número de série, tipo, tensão/corrente nominal.");

        pt("Pos", "Posição do dispositivo de corte (DPS): INTERMEDIÁRIO/ABERTO/FECHADO/DEFEITO. Para XCBR e XSWI. Também é o DO de controle (ctlVal) quando FC=CO.",
            "Pos [ST] = on → fechado; off → aberto");
        pt("NamPlt", "Placa de identificação do nó lógico ou equipamento (CDC LPL). Contém vendor, swRev, hwRev, serNum, model. FC=DC.");
        pt("Health", "Estado de saúde do nó lógico: OK (1), Warning (2), Alarm (3). CDC ENS. Reflete se o LN funciona corretamente.");
        pt("Mod", "Modo de operação do LN (CDC ENC): on, blocked, test, test-blocked, off. O cliente pode alterá-lo com ctlVal. FC=CF para o ajuste, CO para o comando.");
        pt("Beh", "Comportamento real do nó lógico (CDC ENS). Reflete o estado efetivo considerando o Mod e possíveis bloqueios externos.");
        pt("Loc", "Indica se o equipamento está em modo de operação local (TRUE) ou remoto (FALSE). CDC SPS. Quando em LOCAL, comandos remotos podem ser rejeitados.",
            "Loc [ST] = TRUE → somente aceita operação do painel local");
        pt("BlkOpn", "Bloqueia a abertura do disjuntor (SPS). Quando TRUE, o intertravamento CILO rejeita comandos de abertura.");
        pt("BlkCls", "Bloqueia o fechamento do disjuntor (SPS). Quando TRUE, o intertravamento CILO rejeita comandos de fechamento.");
        pt("EnaOpn", "Sinal de habilitação de abertura vindo de CILO. TRUE = a abertura está permitida.");
        pt("EnaCls", "Sinal de habilitação de fechamento vindo de CILO. TRUE = o fechamento está permitido.");
        pt("Auto", "Indica se o equipamento opera em modo automático (TRUE) ou manual (FALSE). SPS.");
        pt("Op", "Indica que a função de proteção operou (disparou). CDC ACT. Inclui general, phsA, phsB, phsC e neut.",
            "PTOC1.Op.general [ST] = TRUE → proteção disparou");
        pt("Str", "Indica que a função de proteção partiu (ultrapassa limiar, inicia temporização). CDC ACT/ACD. Precede o Op se a condição se mantiver.",
            "PDIS1.Str.general [ST] = TRUE → impedância dentro da zona");
        pt("PhV", "Tensões de fase em relação ao neutro (CDC WYE). phsA, phsB, phsC, neut. Cada componente é um CMV com magnitude (mag.f em V) e ângulo (ang.f em °).",
            "MMXU1.PhV.phsA.mag.f [MX] = 127000 V (127 kV no secundário do TP)");
        pt("PPV", "Tensões entre fases (CDC DEL): phsAB, phsBC, phsCA. CMV. Em um sistema de 220 kV, PPV ≈ PhV × √3.");
        pt("A", "Correntes de linha das três fases (CDC WYE): phsA, phsB, phsC, neut. CMV com magnitude em A e ângulo em °.",
            "MMXU1.A.phsA.mag.f [MX] = corrente na fase A");
        pt("W", "Potência ativa trifásica por fase (CDC WYE). Em MW. phsA + phsB + phsC ≈ TotW.");
        pt("VAr", "Potência reativa trifásica por fase (CDC WYE). Em MVAr. Positivo = indutivo (consumo), negativo = capacitivo (geração).");
        pt("VA", "Potência aparente trifásica por fase (CDC WYE). Em MVA. VA = √(W² + VAr²).");
        pt("TotW", "Potência ativa total do sistema trifásico (CDC MV). Em W. Escalar resultante da soma das três fases.",
            "MMXU1.TotW.mag.f [MX] = W (dividir por 1e6 para MW)");
        pt("TotVAr", "Potência reativa total do sistema trifásico (CDC MV). Em VAr.");
        pt("TotVA", "Potência aparente total do sistema trifásico (CDC MV). Em VA.");
        pt("TotPF", "Fator de potência total (CDC MV). Adimensional, faixa [-1, +1]. PF = W / VA. Positivo = indutivo.");
        pt("Hz", "Frequência do sistema elétrico (CDC MV). Em Hz. Nominal: 50 Hz (Europa/América do Sul) ou 60 Hz (América do Norte).",
            "MMXU1.Hz.mag.f [MX] = 50.02 Hz");
        pt("PF", "Fator de potência por fase (CDC WYE). phsA, phsB, phsC. Complementa o TotPF com decomposição por fase.");
        pt("TotWh", "Energia ativa acumulada (CDC BCR). actVal em Wh (INT64). FC=ST. Dividir por 1000 para obter kWh.",
            "MMTR1.TotWh.actVal [ST] / 1000 = kWh importados");
        pt("TotVArh", "Energia reativa acumulada (CDC BCR). actVal em VArh.");
        pt("TotVAh", "Energia aparente acumulada (CDC BCR). actVal em VAh.");
        pt("SeqA", "Componentes simétricas da corrente (CDC SEQ): c1 (positiva), c2 (negativa), c0 (zero). CMV com magnitude e ângulo.",
            "MSQI1.SeqA.c2.mag.f → corrente de sequência negativa (desequilíbrio)");
        pt("SeqV", "Componentes simétricas da tensão (CDC SEQ): c1, c2, c0.");
        pt("ThdA", "Distorção harmônica total da corrente (CDC WYE ou MV). Expressa em % em relação ao fundamental. Limite típico: 5% (IEEE 519).",
            "MHAI1.ThdA.phsA.mag.f [MX] = 8.5 (%)");
        pt("ThdPhV", "Distorção harmônica total da tensão de fase (CDC WYE). Limite típico: 5% (IEEE 519) ou 8% (IEC 61000-2-2).");
        pt("TapPos", "Posição atual do comutador de derivações (CDC INS ou INC). Valor inteiro: 0 = posição média, positivo = eleva tensão.",
            "YLTC1.TapPos.stVal [ST] = 3 (terceiro tap acima do centro)");
        pt("OpCnt", "Contador acumulado de operações de abertura/fechamento (CDC INS). Usado para planejamento de manutenção.");
        pt("OpDlTmms", "Tempo de abertura do disjuntor em milissegundos (CDC MV). Aumenta quando os contatos se desgastam.");
        pt("CBOpCap", "Capacidade de interrupção residual do disjuntor em % (CDC MV). Reduz a cada operação em carga.");

        pt("stVal", "Valor atual do dado de processo: boolean (SPS), inteiro (DPS/INS), float (MV) ou enumerado. É o atributo de leitura principal do CDC.",
            "Pos.stVal = on (fechado), TotW.mag.f = 125000 (W)");
        pt("q", "Indicadores de qualidade do valor: validity (good/invalid/reserved/questionable), overflow, outOfRange, badReference, oscillatory, failure, oldData, inconsistent, inaccurate, source (process/substituted), test, operatorBlocked.",
            "q.validity = good → valor confiável");
        pt("t", "Marca de tempo da última mudança do valor. Resolução de 1 ms, inclui informação de qualidade do relógio (clockNotSynchronized, clockFailure, etc.).",
            "t = 2026-04-23T14:30:00.123Z");
        pt("mag", "Componente de magnitude de um CMV (Complex Measured Value). Contém f (float) ou i (integer).",
            "PhV.phsA.mag.f = 63508.5 (V)");
        pt("ang", "Componente de ângulo de um CMV em graus (°). Referenciado ao fasor de tensão da fase A por convenção.",
            "A.phsA.ang.f = -32.5 (°) → defasagem da corrente em relação à tensão");
        pt("f", "Atributo de ponto flutuante (FLOAT32). Filho de mag ou ang em fasores (CMV), ou de instVal em SAV.");
        pt("i", "Atributo inteiro (INT32). Alternativa a f em magnitudes inteiras (por exemplo, contadores, posições de tap).");
        pt("ctlVal", "Valor que o cliente envia para comandar o processo: boolean (SPC/DPC), inteiro (INC) ou float (APC). FC=CO. É o núcleo da sequência de controle.",
            "Pos.ctlVal = true → comando de fechamento");
        pt("origin", "Indica quem emitiu o comando: orCat (categoria: not-supported/bay-control/station-control/remote-control/automatic-bay/...) e orIdent (identificação).",
            "origin.orCat = remote-control → comando do SCADA");
        pt("ctlNum", "Número de sequência do controle (0-255). O servidor o retorna na resposta para que o cliente identifique a qual comando pertence.");
        pt("operTm", "Horário programado para executar o controle (controle ativado por tempo). Se for zero, o controle é imediato.");
        pt("actVal", "Valor acumulado de um contador BCR (INT64). Para energia: actVal em Wh, dividir por 1000 para kWh.",
            "TotWh.actVal = 12345678 Wh = 12345.678 kWh");
        pt("frVal", "Fração decimal do contador BCR para alta resolução. actVal + frVal/2^32 = valor real do contador.");
        pt("vendor", "Nome do fabricante do IED ou componente. Atributo de NamPlt (LPL). FC=DC.",
            "NamPlt.vendor = 'Siemens AG'");
        pt("swRev", "Versão do firmware/software do IED. Atributo de NamPlt. FC=DC.");
        pt("hwRev", "Versão do hardware do IED. Atributo de NamPlt. FC=DC.");
        pt("serNum", "Número de série único do equipamento. Atributo de NamPlt. FC=DC.");
        pt("model", "Identificação do modelo do equipamento. Atributo de NamPlt. FC=DC.",
            "NamPlt.model = 'SIPROTEC 5 7SL86'");
        pt("d", "Cadeia de texto descritiva do elemento (ASCII). FC=DC. Usada para rotulagem interna do LN ou DO.");
        pt("dU", "Descrição em Unicode do LN ou DO. Permite caracteres não ASCII. FC=DC.");
        pt("db", "Banda morta para trigger por mudança de valor (dchg). Especificada na faixa da unidade de engenharia. FC=CF.",
            "PhV.db = 100 → reportar se a tensão mudar mais de 100 V");
        pt("zeroDb", "Banda morta para valores próximos de zero. FC=CF.");
        pt("smpRate", "Taxa de amostragem de valores SAV (Sampled Values). FC=CF. Típico: 80 ou 256 amostras por ciclo de rede.");
        pt("phsA", "Sub-atributo de WYE ou DEL que contém o fasor da fase A (CMV). Acesso: PhV.phsA.mag.f");
        pt("phsB", "Sub-atributo de WYE que contém o fasor da fase B (CMV).");
        pt("phsC", "Sub-atributo de WYE que contém o fasor da fase C (CMV).");
        pt("neut", "Sub-atributo de WYE que contém o fasor do neutro (CMV).");
        pt("phsAB", "Tensão fase A – fase B no CDC DEL.");
        pt("phsBC", "Tensão fase B – fase C no CDC DEL.");
        pt("phsCA", "Tensão fase C – fase A no CDC DEL.");
        pt("c1", "Componente simétrica de sequência positiva (CMV) no CDC SEQ.",
            "SeqA.c1.mag.f → magnitude da componente de sequência positiva de corrente");
        pt("c2", "Componente simétrica de sequência negativa (CMV) no CDC SEQ. Indica desequilíbrio de carga ou falta entre fases.");
        pt("c0", "Componente simétrica de sequência zero (CMV) no CDC SEQ. Indica corrente de falta à terra.");
        pt("instVal", "Valor de amostra instantânea em SAV (Sampled Value).");
        pt("units", "Unidades de engenharia do valor: SIUnit (W, V, A, Hz, °C...) e multiplier (kilo, mega, milli, etc.). FC=CF.",
            "units.SIUnit = W, units.multiplier = k → kW");
        pt("general", "Atributo de ACT/ACD que indica operação em qualquer fase. general = phsA OR phsB OR phsC.",
            "Str.general = TRUE → partida de proteção em alguma fase");

        pt("FCDA", "Functional Constrained Data Attribute — elemento de um dataset GOOSE ou de relatório. Referência a um atributo específico do modelo com seu FC: ldInst/prefixLNClass.DO.DA [FC].");
        pt("DSET", "Coleção de referências a atributos do modelo (FCDAs). É a 'lista de variáveis' publicada via GOOSE ou incluída em relatórios (RCB). Definido no LLN0 de cada LD.");
        pt("GOCB", "Parâmetros da mensagem GOOSE: GoID, AppID, destMAC, confRev, dataset. Publicado pelo IED em Ethernet multicast quando há mudança no dataset.");
        pt("URCB", "Relatório sem buffer: o servidor envia o relatório imediatamente. Se o cliente não estiver conectado, o evento é perdido. Apenas um cliente pode se inscrever por vez em um URCB.");
        pt("BRCB", "Relatório com buffer: o servidor armazena os eventos mesmo que o cliente esteja desconectado. Ao reconectar, o cliente recebe todos os relatórios pendentes. Essencial para aplicações críticas.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Traducciones al inglés (descripcion/ejemplo) — 2026-07-20
    //  Mismo mecanismo que ZH/PT: no toca las entradas originales. Terminos clave
    //  IEC 61850 se dejan sin traducir dentro del texto ingles.
    // ─────────────────────────────────────────────────────────────────────────
    static {
        en("LLN0", "Root node of the Logical Device. Contains the report control blocks (RCB), GOOSE control blocks (GoCB), datasets and the general configuration of the LD.",
            "LLN0.Mod (operating mode), LLN0.NamPlt (nameplate)");
        en("LPHD", "Represents the actual physical device (IED). Contains inventory information (vendor, model, firmware version), health status and communication.",
            "LPHD1.NamPlt (vendor/model), LPHD1.PhyHealth (hardware status)");

        en("CSWI", "Controls the operation (open/close) of a circuit breaker or disconnector. Receives SBO (Select-Before-Operate) or direct commands and interacts with CILO to check that the interlocking allows the maneuver.",
            "CSWI1.Pos (position), CSWI1.Auto (automatic mode)");
        en("CILO", "Implements the interlocking logic. Evaluates safety conditions (position of other breakers, presence of voltage, etc.) before authorizing a maneuver. Blocks or enables the command over CSWI.",
            "CILO1.EnaOpn (enable opening), CILO1.EnaCls (enable closing)");
        en("CALH", "Processes and prioritizes process and system alarms. Can group, filter and escalate alarms, and keeps a historical event log.");
        en("CCGR", "Controls the cooling systems of transformers or other equipment (pumps, fans). Activates stages according to temperature.");
        en("CPOW", "Detects inter-area power swings and can block distance protections during the swing to prevent incorrect trips.");
        en("CSYN", "Checks that synchronism conditions (voltage difference, frequency and phase angle) are acceptable before closing a breaker and connecting two electrical systems.");

        en("XCBR", "Represents the physical power circuit breaker. Reports its real position (Pos: intermediate/open/closed/faulty), operation counters, opening/closing time and drive status.",
            "XCBR1.Pos [ST] = on/off, XCBR1.OpCnt (operation counter)");
        en("XSWI", "Represents a switching device without fault-current interruption capability: disconnector, earthing switch, bypass, etc. Similar to XCBR but without an interrupting arc.",
            "XSWI1.Pos [ST], XSWI1.Loc (local operation)");

        en("PDIS", "Measures the impedance seen by the relay and operates if it falls within a predefined zone (Z1, Z2, Z3…). Main protection for transmission lines.",
            "PDIS1.Op (operate), PDIS1.Str (start), PDIS1.Z1 (zone 1)");
        en("PDIF", "Compares the input and output currents of a protected element (transformer, generator, busbar, cable). Operates when the difference exceeds the threshold, indicating an internal fault.",
            "PDIF1.Op, PDIF1.Str, PDIF1.RsDif (differential start current)");
        en("PTOC", "Overcurrent protection with inverse-time or definite-time characteristic. Trips when the current exceeds the start setting (Str) after the time computed by the curve.",
            "PTOC1.Str (start), PTOC1.Op (operate/trip), PTOC1.DirMod (directional)");
        en("PIOC", "Instantaneous overcurrent protection (no intentional delay). Covers high-current faults, typically the 50 element of electromechanical relays.",
            "PIOC1.Op (operate), PIOC1.Str (start)");
        en("PTOV", "Operates when the voltage exceeds the set level for the time defined by the curve or fixed delay. Protects equipment sensitive to overvoltage.");
        en("PTUV", "Operates when the voltage falls below the set level. Detects loss of voltage, prolonged sags or network failure.");
        en("PTRC", "Centralizes and conditions trips from multiple protection functions before sending the order to the breaker. Allows AND/OR logic, blocking and trip sequencing.",
            "PTRC1.Tr (general trip), PTRC1.Op1...Op3 (individual trips)");
        en("PSCH", "Coordinates multiple protection functions as a complete scheme (line differential, transfer trip, zone acceleration, etc.).");
        en("PHAR", "Blocks or restrains transformer differential protection during energization (inrush) by detecting the high 2nd-harmonic component.");
        en("PHIZ", "Detects high-impedance faults to earth (fallen tree, wet pavement) that do not produce enough current for conventional relays.");
        en("PTEF", "Time-graded earth-fault protection. Detects zero-sequence or residual currents that indicate an earth fault.");
        en("PSDE", "Sensitive directional earth-fault protection for networks with isolated or compensated (Petersen) neutral. Detects very small fault currents.");
        en("PRTR", "Restricted earth-fault differential protection for a transformer winding. Compares the neutral current with the sum of the phase currents.");
        en("PDOP", "Operates when the active power flow in one direction exceeds the setting. Useful for generators and interconnections.");
        en("PUPF", "Operates when the power factor falls below the setting. Protects generators against operation in an excessive reactive-absorption zone.");
        en("PVOC", "Time-graded overcurrent whose start setting automatically varies with voltage, useful for generators with voltage restraint during faults.");
        en("PZSU", "Detects that a rotating machine is below rated speed or fully stopped, protecting against incomplete start or loss of synchronism.");
        en("PVPH", "Protects transformers and generators against over-excitation by detecting a V/Hz ratio above the setting.");
        en("PTOF", "Operates when the system frequency exceeds the setting. Used in generators and frequency control schemes.");
        en("PTTR", "Models the heating of the equipment (transformer, cable, motor) and operates before the temperature damages the insulation.");

        en("RBRF", "If the breaker does not open after receiving the trip order, initiates a back-up trip on the adjacent breakers to isolate the fault.",
            "RBRF1.Op (back-up operation), RBRF1.StrBF (breaker-failure start)");
        en("RREC", "After a protection trip, attempts to automatically restore the line with 1, 2 or 3 reclosing attempts (single-phase or three-phase) and configurable dead times.",
            "RREC1.Op (successful reclosing), RREC1.Str (start), RREC1.Auto (enabled)");
        en("RSYN", "Checks whether the voltage, frequency and angle difference between the two sides of a breaker is acceptable before authorizing closing.");
        en("RPSB", "Blocks the distance protection during power swings to prevent unwanted trips.");
        en("RDRE", "Captures voltage and current waveforms before and after an event. Stores records in COMTRADE format for post-fault analysis.",
            "RDRE1.Op (record trigger), RDRE1.ChNum (number of channels)");
        en("RFLO", "Calculates the distance to the fault on a transmission line using the voltage and current measurements at the moment of the event.");

        en("MMXU", "Main measurement node: phase and phase-to-phase voltages, phase currents, three-phase active/reactive/apparent power, power factor and frequency. The most widely used node in meters and measurement relays.",
            "MMXU1.PhV (per-phase voltage, WYE), MMXU1.A (current, WYE), MMXU1.TotW (total active power), MMXU1.Hz (frequency)");
        en("MMXN", "Measurement of electrical quantities that are not decomposed by phase: neutral current, zero-sequence voltage, etc.");
        en("MHAI", "Measures the harmonic and inter-harmonic content of per-phase voltages and currents (up to the 50th harmonic). Includes THD and distortion factors. Crucial for power-quality analysis.",
            "MHAI1.ThdA (current THD), MHAI1.HKf (dominant harmonic order)");
        en("MHAN", "Similar to MHAI, but for quantities without phase decomposition (neutral, zero sequence).");
        en("MSQI", "Calculates symmetrical components (positive, negative and zero sequence) of voltages and currents, and unbalance indices.",
            "MSQI1.SeqA (current sequence), MSQI1.SeqV (voltage sequence)");
        en("MMTR", "Integrates active, reactive and apparent energy (imported and exported). The counters are of BCR type (Binary Counter Reading), FC=ST.",
            "MMTR1.TotWh (active energy Wh), MMTR1.TotVArh, MMTR1.TotVAh");
        en("MSTA", "Calculates demand values (time-interval average): average power, maximum demand, load factor, etc.");
        en("MDIF", "Monitors the current difference between two points without a trip function. Used for continuous integrity supervision.");
        en("MFLK", "Measures voltage flicker per IEC 61000-4-15: Pst (short term, 10 min) and Plt (long term, 2 hours).");

        en("ATCC", "Controls the tap changer of a transformer to regulate the voltage on the secondary side within a setting range.",
            "ATCC1.TapPos (current tap position), ATCC1.Auto (automatic mode)");
        en("AVCO", "Coordinates multiple tap-changing transformers or capacitor banks to regulate the voltage on a bus.");
        en("ARCO", "Regulates the reactive power injected or absorbed (capacitor banks, SVCs, generators) to maintain voltage and power factor.");

        en("GAPC", "Generic node for process control logic not covered by other LNs. Has generic digital and analog inputs/outputs.");
        en("GGIO", "Exposes general-purpose digital/analog inputs and outputs of the IED. Widely used for status signals, alarms and miscellaneous commands.",
            "GGIO1.Ind1...Ind16 (digital indications), GGIO1.AnIn1 (analog input)");
        en("GSAL", "Generates alarms related to the IED's cybersecurity: unauthorized access, configuration integrity, etc.");

        en("YTRF", "Models the power transformer: turns ratio, vector group, temperature, oil level, dissolved gas (DGA), etc.",
            "YTRF1.NamPlt (nameplate), YTRF1.TmpHot (hot-spot temperature)");
        en("YLTC", "Represents the on-load tap changer (OLTC). Reports the current position, limits and operation counter.",
            "YLTC1.TapPos (position), YLTC1.TapChg (tap change)");
        en("YEFN", "Controls the Petersen coil (resonant earthing reactor) to compensate the capacitive current in networks with isolated/compensated neutral.");
        en("YPSH", "Represents a capacitor bank, shunt reactor or other element connected in parallel for reactive compensation.");

        en("ZLIN", "Models the transmission line: length, impedance, capacitance, rated current. Serves as a reference for the fault locator.");
        en("ZGEN", "Models the electrical generator: rated power, rated power factor, voltage, speed. May include a capability curve.");
        en("ZMOT", "Models an electric motor: rated current, power factor, winding temperature, number of starts.");
        en("ZCAP", "Represents a shunt capacitor bank for reactive compensation. Includes number of stages, rated reactive power and status of each stage.");
        en("ZBAT", "Monitors the backup battery or UPS of the IED or the substation: voltage, current, state of charge, temperature.");
        en("ZSAR", "Models the zinc-oxide surge arrester. May include discharge counter, leakage current and temperature.");

        en("TCTR", "Models the CT: turns ratio, accuracy class, rated burden, saturation factor. Required for accurate substation models.",
            "TCTR1.Rat (ratio), TCTR1.A (measured secondary current)");
        en("TVTR", "Models the VT: turns ratio, accuracy class, rated burden and voltage factor.",
            "TVTR1.Rat (ratio), TVTR1.Vol (measured secondary voltage)");

        en("STMP", "Supervises temperatures at equipment points (oil, winding, ambient, hot spot) with alarms and trips on over-temperature.");
        en("SCBR", "Supervises the mechanical and electrical status of the breaker: SF6 pressure, spring pressure, oil level, contact wear.");
        en("SIMG", "Supervises the SF6 gas of interrupting chambers: pressure, density, temperature and low-level alarms.");
        en("SOPM", "Supervises operating parameters: trip counter, accumulated interrupting current, number of no-load operations.");

        en("ST", "Real-time process status values. These are the most-read data: breaker position, alarms, quality and protection indications. Accessible by the client and automatically reported via RCB.",
            "XCBR1.Pos [ST], MMXU1.TotW [ST]");
        en("MX", "Analog process values: voltage, current, power, frequency, etc. Updated periodically or on significant change. Reported via RCB or read by polling.",
            "MMXU1.PhV [MX], MMXU1.A [MX], MMXU1.Hz [MX]");
        en("CO", "Attributes used in control sequences: control value (ctlVal), origin, sequence number (ctlNum), operate time (operTm). The client writes these attributes to send commands to the IED.",
            "XCBR1.Pos.ctlVal [CO] — sends an open/close command");
        en("CF", "Configuration parameters of the logical node: deadband, integrity period, time constants. Normally written during commissioning.",
            "MMXU1.PhV.db [CF] — deadband for change-triggered reports");
        en("DC", "Nameplate data of the LN: vendor, model, software version, serial number. Read-only. Typical access: F=DC or F=LPL.",
            "XCBR1.NamPlt [DC]");
        en("SG", "Values of the currently active setting group. The client can read the active settings but cannot modify them here (use SE to edit).",
            "PTOC1.StrVal [SG] — active setting's start threshold");
        en("SE", "Access to the setting group being edited. The client can modify these values before activating them with the ConfSG command.");
        en("SP", "Setpoint the client can modify directly (without prior selection). Unlike SG, it is an operational value, not a setting group.");
        en("EX", "Extended definition attributes, specific to the vendor or the application. Not part of the standard normative model.");
        en("OR", "Indicates that an operate order has been received. Used internally to track commands in SBO (Select-Before-Operate) sequences.");
        en("BL", "Blocks the value of an attribute, preventing its update. Used in maintenance tests (blkEna=true). The frozen value remains even if the process changes.");

        en("SPS", "Boolean process-status bit: TRUE or FALSE. Contains: stVal (value), q (quality) and t (timestamp). Example: breaker interlocked, alarm signal, mode enable.",
            "BlkOpn, BlkCls, Loc, Auto, EnaOpn, EnaCls");
        en("DPS", "Process status with four values: INTERMEDIATE (00), OPEN (01), CLOSED (10), FAULTY (11). The standard CDC for breaker and disconnector position.",
            "Pos (XCBR/XSWI position) — intermediate during the maneuver");
        en("INS", "Integer process-status value with quality and timestamp. Used for tap positions, operation counters, etc.",
            "TapPos (tap changer position), OpCnt (counter)");
        en("ENS", "Enumerated status value: operating mode (on/blocked/test/off), health status (OK/Warning/Alarm), LN behavior.",
            "Mod (mode: on/blocked/test/test-blocked/off), Beh, Health");
        en("ACT", "Indicates that a protection function has operated. Includes: general, per phase (phsA/phsB/phsC) and direction.",
            "Op (trip operate), Str (start)");
        en("ACD", "Extends ACT with directionality information: forward, backward, both. Used in directional protections (distance, directional earth fault).");
        en("SEC", "Counter with quality and timestamp for security events. Used in GSAL nodes.");
        en("BCR", "Binary accumulator counter reading: actVal (accumulated value, INT64), frVal (fractional value), q (quality) and t (reading timestamp). FC=ST. Divide by 1000 to convert Wh → kWh.",
            "TotWh.actVal [ST] / 1000 = kWh");
        en("MV", "Scalar analog value: mag.f (floating point) or mag.i (integer), q (quality) and t (timestamp). The simplest CDC for a quantity without phase.",
            "TotW, TotVAr, Hz, PF → all are MV");
        en("CMV", "Phasor value: magnitude (mag.f) and angle (ang.f). Used to represent voltage and current phasors individually.",
            "SEQ components: c1, c2, c0 are CMV");
        en("SAV", "Instantaneous voltage or current sample, used mainly in Sampled Values (SVs). Includes instVal (instantaneous value).");
        en("WYE", "Groups three phase phasors (phsA, phsB, phsC) and the neutral (neut), each of type CMV. The standard CDC for phase voltages and line currents in a wye (star) connection.",
            "PhV (phase voltages), A (line currents), W, VAr, VA, PF");
        en("DEL", "Groups three phase-to-phase phasors (phsAB, phsBC, phsCA), each CMV. Used for line (phase-to-phase) voltages in a delta connection.",
            "PPV (phase-to-phase voltage)");
        en("SEQ", "Symmetrical decomposition: c1 (positive sequence), c2 (negative), c0 (zero sequence), each component of type CMV with magnitude and angle.",
            "SeqV.c1 (positive-sequence voltage), SeqA.c2 (negative-sequence current)");
        en("SPC", "Boolean control point: the client sends ctlVal=TRUE/FALSE. The process responds by changing stVal. CDC for simple digital outputs.");
        en("DPC", "Position control: the client commands CLOSE (01) or OPEN (10). The response includes the intermediate states. The standard CDC for breaker/disconnector commands.",
            "Pos of XCBR/XSWI — stVal is DPS, ctlVal is SPC (open/close)");
        en("INC", "Allows the client to write an integer value. Used for manual tap changes, setting-group number selection, etc.",
            "TapPos with control, SetGrp (setting-group selection)");
        en("ENC", "Enumerated control: the client sends one of the values defined in the model. Typical for Mod (LN mode).",
            "Mod.ctlVal = on/blocked/test/test-blocked/off");
        en("BSC", "Step-position control: 'raise' or 'lower' commands. Used for step-controlled tap changers.");
        en("APC", "Allows the client to write an analog (float) value. Used for regulator setpoints: target voltage, power, etc.");
        en("BAC", "Analog setpoint controlled by binary steps (raise/lower). Combines APC and BSC.");
        en("LPL", "Identification data of the LN or physical equipment: vendor, model (swRev, hwRev), serial number (serNum), description (d), location (loc), IED type (model).",
            "NamPlt of LLN0 and LPHD");
        en("DPL", "Nameplate data of the physical device or field component: vendor, serial number, type, rated voltage/current.");

        en("Pos", "Position of the switching device (DPS): INTERMEDIATE/OPEN/CLOSED/FAULTY. For XCBR and XSWI. Also the control DO (ctlVal) when FC=CO.",
            "Pos [ST] = on → closed; off → open");
        en("NamPlt", "Nameplate of the logical node or equipment (CDC LPL). Contains vendor, swRev, hwRev, serNum, model. FC=DC.");
        en("Health", "Health status of the logical node: OK (1), Warning (2), Alarm (3). CDC ENS. Reflects whether the LN is functioning correctly.");
        en("Mod", "Operating mode of the LN (CDC ENC): on, blocked, test, test-blocked, off. The client can change it with ctlVal. FC=CF for the setting, CO for the command.");
        en("Beh", "Actual behavior of the logical node (CDC ENS). Reflects the effective state considering Mod and any external blocking.");
        en("Loc", "Indicates whether the equipment is in local (TRUE) or remote (FALSE) operating mode. CDC SPS. When in LOCAL, remote commands may be rejected.",
            "Loc [ST] = TRUE → only accepts local-panel operation");
        en("BlkOpn", "Blocks breaker opening (SPS). When TRUE, the CILO interlocking rejects opening commands.");
        en("BlkCls", "Blocks breaker closing (SPS). When TRUE, the CILO interlocking rejects closing commands.");
        en("EnaOpn", "Opening-enable signal from CILO. TRUE = opening is permitted.");
        en("EnaCls", "Closing-enable signal from CILO. TRUE = closing is permitted.");
        en("Auto", "Indicates whether the equipment operates in automatic (TRUE) or manual (FALSE) mode. SPS.");
        en("Op", "Indicates that the protection function operated (tripped). CDC ACT. Includes general, phsA, phsB, phsC and neut.",
            "PTOC1.Op.general [ST] = TRUE → protection tripped");
        en("Str", "Indicates that the protection function started (threshold exceeded, timing initiated). CDC ACT/ACD. Precedes Op if the condition persists.",
            "PDIS1.Str.general [ST] = TRUE → impedance within zone");
        en("PhV", "Phase-to-neutral voltages (CDC WYE). phsA, phsB, phsC, neut. Each component is a CMV with magnitude (mag.f in V) and angle (ang.f in °).",
            "MMXU1.PhV.phsA.mag.f [MX] = 127000 V (127 kV on the VT secondary)");
        en("PPV", "Phase-to-phase voltages (CDC DEL): phsAB, phsBC, phsCA. CMV. In a 220 kV system, PPV ≈ PhV × √3.");
        en("A", "Line currents of the three phases (CDC WYE): phsA, phsB, phsC, neut. CMV with magnitude in A and angle in °.",
            "MMXU1.A.phsA.mag.f [MX] = current in phase A");
        en("W", "Three-phase active power per phase (CDC WYE). In MW. phsA + phsB + phsC ≈ TotW.");
        en("VAr", "Three-phase reactive power per phase (CDC WYE). In MVAr. Positive = inductive (consumption), negative = capacitive (generation).");
        en("VA", "Three-phase apparent power per phase (CDC WYE). In MVA. VA = √(W² + VAr²).");
        en("TotW", "Total active power of the three-phase system (CDC MV). In W. Scalar resulting from the sum of the three phases.",
            "MMXU1.TotW.mag.f [MX] = W (divide by 1e6 for MW)");
        en("TotVAr", "Total reactive power of the three-phase system (CDC MV). In VAr.");
        en("TotVA", "Total apparent power of the three-phase system (CDC MV). In VA.");
        en("TotPF", "Total power factor (CDC MV). Dimensionless, range [-1, +1]. PF = W / VA. Positive = inductive.");
        en("Hz", "Frequency of the electrical system (CDC MV). In Hz. Nominal: 50 Hz (Europe/South America) or 60 Hz (North America).",
            "MMXU1.Hz.mag.f [MX] = 50.02 Hz");
        en("PF", "Power factor per phase (CDC WYE). phsA, phsB, phsC. Complements TotPF with a per-phase breakdown.");
        en("TotWh", "Accumulated active energy (CDC BCR). actVal in Wh (INT64). FC=ST. Divide by 1000 to get kWh.",
            "MMTR1.TotWh.actVal [ST] / 1000 = kWh imported");
        en("TotVArh", "Accumulated reactive energy (CDC BCR). actVal in VArh.");
        en("TotVAh", "Accumulated apparent energy (CDC BCR). actVal in VAh.");
        en("SeqA", "Symmetrical components of the current (CDC SEQ): c1 (positive), c2 (negative), c0 (zero). CMV with magnitude and angle.",
            "MSQI1.SeqA.c2.mag.f → negative-sequence current (unbalance)");
        en("SeqV", "Symmetrical components of the voltage (CDC SEQ): c1, c2, c0.");
        en("ThdA", "Total harmonic distortion of the current (CDC WYE or MV). Expressed in % relative to the fundamental. Typical limit: 5% (IEEE 519).",
            "MHAI1.ThdA.phsA.mag.f [MX] = 8.5 (%)");
        en("ThdPhV", "Total harmonic distortion of the phase voltage (CDC WYE). Typical limit: 5% (IEEE 519) or 8% (IEC 61000-2-2).");
        en("TapPos", "Current position of the tap changer (CDC INS or INC). Integer value: 0 = mid position, positive = raises voltage.",
            "YLTC1.TapPos.stVal [ST] = 3 (third tap above center)");
        en("OpCnt", "Accumulated counter of open/close operations (CDC INS). Used for maintenance planning.");
        en("OpDlTmms", "Breaker opening time in milliseconds (CDC MV). Increases as the contacts wear.");
        en("CBOpCap", "Remaining interrupting capability of the breaker in % (CDC MV). Decreases with each operation under load.");

        en("stVal", "Current value of the process data: boolean (SPS), integer (DPS/INS), float (MV) or enumerated. The main read attribute of the CDC.",
            "Pos.stVal = on (closed), TotW.mag.f = 125000 (W)");
        en("q", "Quality indicators of the value: validity (good/invalid/reserved/questionable), overflow, outOfRange, badReference, oscillatory, failure, oldData, inconsistent, inaccurate, source (process/substituted), test, operatorBlocked.",
            "q.validity = good → reliable value");
        en("t", "Timestamp of the last value change. 1 ms resolution, includes clock-quality information (clockNotSynchronized, clockFailure, etc.).",
            "t = 2026-04-23T14:30:00.123Z");
        en("mag", "Magnitude component of a CMV (Complex Measured Value). Contains f (float) or i (integer).",
            "PhV.phsA.mag.f = 63508.5 (V)");
        en("ang", "Angle component of a CMV in degrees (°). Referenced to the phase-A voltage phasor by convention.",
            "A.phsA.ang.f = -32.5 (°) → current phase shift relative to voltage");
        en("f", "Floating-point attribute (FLOAT32). Child of mag or ang in phasors (CMV), or of instVal in SAV.");
        en("i", "Integer attribute (INT32). Alternative to f for integer magnitudes (e.g., counters, tap positions).");
        en("ctlVal", "Value the client sends to command the process: boolean (SPC/DPC), integer (INC) or float (APC). FC=CO. The core of the control sequence.",
            "Pos.ctlVal = true → close command");
        en("origin", "Indicates who issued the command: orCat (category: not-supported/bay-control/station-control/remote-control/automatic-bay/...) and orIdent (identification).",
            "origin.orCat = remote-control → command from SCADA");
        en("ctlNum", "Control sequence number (0-255). The server returns it in the response so the client can identify which command it belongs to.");
        en("operTm", "Scheduled time to execute the control (time-activated control). If zero, the control is immediate.");
        en("actVal", "Accumulated value of a BCR counter (INT64). For energy: actVal in Wh, divide by 1000 for kWh.",
            "TotWh.actVal = 12345678 Wh = 12345.678 kWh");
        en("frVal", "Decimal fraction of the BCR counter for high resolution. actVal + frVal/2^32 = actual counter value.");
        en("vendor", "Name of the IED or component manufacturer. NamPlt (LPL) attribute. FC=DC.",
            "NamPlt.vendor = 'Siemens AG'");
        en("swRev", "Firmware/software version of the IED. NamPlt attribute. FC=DC.");
        en("hwRev", "Hardware version of the IED. NamPlt attribute. FC=DC.");
        en("serNum", "Unique serial number of the equipment. NamPlt attribute. FC=DC.");
        en("model", "Model identification of the equipment. NamPlt attribute. FC=DC.",
            "NamPlt.model = 'SIPROTEC 5 7SL86'");
        en("d", "Descriptive text string of the element (ASCII). FC=DC. Used for internal labeling of the LN or DO.");
        en("dU", "Unicode description of the LN or DO. Allows non-ASCII characters. FC=DC.");
        en("db", "Deadband for change-triggered (dchg) reporting. Specified in the engineering-unit range. FC=CF.",
            "PhV.db = 100 → report if the voltage changes by more than 100 V");
        en("zeroDb", "Deadband for values near zero. FC=CF.");
        en("smpRate", "Sampling rate of SAV (Sampled Values). FC=CF. Typical: 80 or 256 samples per network cycle.");
        en("phsA", "Sub-attribute of WYE or DEL containing the phase-A phasor (CMV). Access: PhV.phsA.mag.f");
        en("phsB", "Sub-attribute of WYE containing the phase-B phasor (CMV).");
        en("phsC", "Sub-attribute of WYE containing the phase-C phasor (CMV).");
        en("neut", "Sub-attribute of WYE containing the neutral phasor (CMV).");
        en("phsAB", "Phase A – phase B voltage in the DEL CDC.");
        en("phsBC", "Phase B – phase C voltage in the DEL CDC.");
        en("phsCA", "Phase C – phase A voltage in the DEL CDC.");
        en("c1", "Positive-sequence symmetrical component (CMV) in the SEQ CDC.",
            "SeqA.c1.mag.f → magnitude of the positive-sequence current component");
        en("c2", "Negative-sequence symmetrical component (CMV) in the SEQ CDC. Indicates load unbalance or a fault between phases.");
        en("c0", "Zero-sequence symmetrical component (CMV) in the SEQ CDC. Indicates earth-fault current.");
        en("instVal", "Instantaneous sample value in SAV (Sampled Value).");
        en("units", "Engineering units of the value: SIUnit (W, V, A, Hz, °C...) and multiplier (kilo, mega, milli, etc.). FC=CF.",
            "units.SIUnit = W, units.multiplier = k → kW");
        en("general", "ACT/ACD attribute indicating operation in any phase. general = phsA OR phsB OR phsC.",
            "Str.general = TRUE → protection started in some phase");

        en("FCDA", "Functional Constrained Data Attribute — an element of a GOOSE or report dataset. A reference to a specific model attribute with its FC: ldInst/prefixLNClass.DO.DA [FC].");
        en("DSET", "Collection of references to model attributes (FCDAs). The 'variable list' published via GOOSE or included in reports (RCB). Defined in the LLN0 of each LD.");
        en("GOCB", "Parameters of the GOOSE message: GoID, AppID, destMAC, confRev, dataset. Published by the IED over Ethernet multicast when the dataset changes.");
        en("URCB", "Unbuffered report: the server sends the report immediately. If the client is not connected, the event is lost. Only one client can subscribe to a given URCB at a time.");
        en("BRCB", "Buffered report: the server stores events even if the client is disconnected. Upon reconnecting, the client receives all pending reports. Essential for critical applications.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Métodos de inserción internos
    // ─────────────────────────────────────────────────────────────────────────
    private static void ln(String key, String es, String en, String desc, String std) {
        DICT.put(key.toUpperCase(), new Entry(EntryType.LOGICAL_NODE, es, en, desc, std));
    }
    private static void ln(String key, String es, String en, String desc, String std, String ex) {
        DICT.put(key.toUpperCase(), new Entry(EntryType.LOGICAL_NODE, es, en, desc, std, ex));
    }
    private static void fc(String key, String es, String desc, String std, String ex) {
        DICT.put(key.toUpperCase(), new Entry(EntryType.FC, es, key + " (Functional Constraint)", desc, std, ex));
    }
    private static void fc(String key, String es, String desc, String std) {
        DICT.put(key.toUpperCase(), new Entry(EntryType.FC, es, key + " (Functional Constraint)", desc, std));
    }
    private static void cdc(String key, String es, String desc, String std) {
        DICT.put(key.toUpperCase(), new Entry(EntryType.CDC, es, key + " (Common Data Class)", desc, std));
    }
    private static void cdc(String key, String es, String desc, String std, String ex) {
        DICT.put(key.toUpperCase(), new Entry(EntryType.CDC, es, key + " (Common Data Class)", desc, std, ex));
    }
    private static void cdc(String key, String es, String en, String desc, String std, String ex) {
        DICT.put(key.toUpperCase(), new Entry(EntryType.CDC, es, en, desc, std, ex));
    }
    private static void doEntry(String key, String es, String en, String desc, String std) {
        DICT.put(key, new Entry(EntryType.DATA_OBJECT, es, en, desc, std));
    }
    private static void doEntry(String key, String es, String en, String desc, String std, String ex) {
        DICT.put(key, new Entry(EntryType.DATA_OBJECT, es, en, desc, std, ex));
    }
    private static void da(String key, String es, String en, String desc, String std) {
        DICT.put(key, new Entry(EntryType.DATA_ATTRIBUTE, es, en, desc, std));
    }
    private static void da(String key, String es, String en, String desc, String std, String ex) {
        DICT.put(key, new Entry(EntryType.DATA_ATTRIBUTE, es, en, desc, std, ex));
    }
    private static void special(String key, String es, String desc, String std) {
        DICT.put(key.toUpperCase(), new Entry(EntryType.SPECIAL, es, key, desc, std));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Lookup
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Busca una entrada por nombre de nodo. Lógica:
     * 1. Exacto
     * 2. Sin dígitos finales (XCBR1 → XCBR)
     * 3. Exacto en mayúsculas
     * 4. Sin dígitos en mayúsculas
     * 5. null si no encontrado
     */
    static Entry lookup(String name) {
        if (name == null || name.isBlank()) return null;
        name = name.trim();

        // Limpiar suffijos de instancia o suffijos entre corchetes
        // p.ej. "[1] LD0/MMXU1.TotW [MX]" → extraer el token relevante
        // El nombre viene como NodeInfo.name que puede ser "XCBR1", "TotW", "phsA", "ST", etc.

        Entry e;
        // Exacto
        if ((e = DICT.get(name)) != null) return e;
        // Sin dígitos finales
        String noDigits = name.replaceAll("\\d+$", "");
        if (!noDigits.equals(name) && (e = DICT.get(noDigits)) != null) return e;
        // Mayúsculas
        if ((e = DICT.get(name.toUpperCase())) != null) return e;
        // Sin dígitos + mayúsculas
        if (!noDigits.equals(name) && (e = DICT.get(noDigits.toUpperCase())) != null) return e;
        // ── Inferencia por sufijo para nombres con prefijo de fabricante ────────
        // Algunos fabricantes extienden el nombre del LN con un prefijo propio:
        //   "BK1AXCBR1" → prefijo "BK1A", clase "XCBR", instancia "1"
        //   "DC7CILO7"  → prefijo "DC7",  clase "CILO",  instancia "7"
        //   "OSB1RPSB2" → prefijo "OSB1", clase "RPSB",  instancia "2"
        //   "R32PRDIR6" → prefijo "R32",  clase "PRDIR", instancia "6"
        //
        // Algoritmo: eliminar dígitos al final, luego probar sufijos del nombre base
        // de mayor a menor longitud. Solo se acepta si la entrada es LOGICAL_NODE.
        String base = name.toUpperCase().replaceAll("\\d+$", ""); // "BK1AXCBR"
        for (int start = 0; start < base.length() - 1; start++) {
            String suffix = base.substring(start);                 // "BK1AXCBR","K1AXCBR",..."XCBR"
            Entry candidate = DICT.get(suffix);
            if (candidate != null && candidate.type == EntryType.LOGICAL_NODE) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Infiere la clase LN (ej. "XCBR", "MMXU") desde un nombre con posible prefijo de fabricante.
     * Ejemplos: "BK1AXCBR1" → "XCBR", "DC7CILO7" → "CILO", "MMXU1" → "MMXU".
     * Retorna null si no se reconoce ninguna clase LN conocida.
     */
    static String inferLnClass(String name) {
        if (name == null || name.isBlank()) return null;
        String base = name.trim().toUpperCase().replaceAll("\\d+$", "");
        Entry e = DICT.get(base);
        if (e != null && e.type == EntryType.LOGICAL_NODE) return base;
        for (int start = 1; start < base.length(); start++) {
            String suffix = base.substring(start);
            e = DICT.get(suffix);
            if (e != null && e.type == EntryType.LOGICAL_NODE) return suffix;
        }
        return null;
    }

    /**
     * Acceso sin Swing para el bridge: descripción del token como mapa serializable.
     * Retorna null si el token no está en el diccionario (ni por inferencia).
     */
    public static Map<String, String> describe(String name) {
        Entry e = lookup(name);
        if (e == null) return null;
        Map<String, String> m = new LinkedHashMap<>();
        m.put("token", name == null ? "" : name.trim());
        m.put("kind", e.type.badge);
        m.put("kindLabel", e.type.label());
        m.put("nameEs", e.fullNameES);
        m.put("nameEn", e.fullNameEN);
        m.put("description", e.description);
        m.put("standard", e.standard);
        if (e.example != null) m.put("example", e.example);
        String inferred = inferLnClass(name);
        if (inferred != null) m.put("lnClass", inferred);
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Diálogo de ayuda
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Muestra el diálogo educativo IEC 61850 para el nombre de nodo dado.
     * Si no hay entrada en el diccionario muestra un mensaje genérico.
     */
    /** Callback que recibe el diálogo de descripción para posicionar la leyenda al lado. */
    @FunctionalInterface
    interface LegendAction { void open(java.awt.Dialog infoDialog); }

    static void showInfoDialog(Component parent, String nodeName) {
        showInfoDialog(parent, nodeName, null);
    }

    static void showInfoDialog(Component parent, String nodeName, LegendAction legendAction) {
        Entry entry = lookup(nodeName);

        // Detectar si el match fue por inferencia parcial
        // (el nombre sin dígitos no coincide directamente con la clase inferida)
        String inferredClass = inferLnClass(nodeName);
        String stripped = nodeName.replaceAll("\\d+$", "").toUpperCase();
        boolean isInferred = entry != null
                && inferredClass != null
                && !stripped.equals(inferredClass);

        boolean modal = (legendAction == null);
        JDialog dialog = new JDialog(
            (Frame) SwingUtilities.getWindowAncestor(parent),
            I18n.t("dict.dialog.title"), modal);
        dialog.setSize(600, 480);
        dialog.setLocationRelativeTo(parent);
        dialog.setResizable(true);

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(Color.WHITE);
        dialog.setContentPane(root);

        // ── Header ──────────────────────────────────────────────────────────
        JPanel header = new JPanel(new BorderLayout(10, 6));
        header.setBorder(new EmptyBorder(14, 18, 10, 18));

        Color headerColor = entry != null ? entry.type.color : new Color(0x546E7A);
        header.setBackground(headerColor);

        // Badge de tipo
        EntryType etype = entry != null ? entry.type : EntryType.SPECIAL;
        JLabel badge = new JLabel(etype.badge);
        badge.setFont(new Font("Monospaced", Font.BOLD, 13));
        badge.setForeground(Color.WHITE);
        badge.setOpaque(true);
        badge.setBackground(headerColor.darker());
        badge.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(255, 255, 255, 80), 1),
            new EmptyBorder(3, 8, 3, 8)));

        JLabel nameLabel = new JLabel(nodeName);
        nameLabel.setFont(new Font("SansSerif", Font.BOLD, 22));
        nameLabel.setForeground(Color.WHITE);

        JPanel badgeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        badgeRow.setOpaque(false);
        badgeRow.add(badge);

        header.add(badgeRow, BorderLayout.NORTH);
        header.add(nameLabel, BorderLayout.CENTER);

        if (entry != null) {
            String typeText = entry.type.label()
                + (isInferred ? "  ·  " + I18n.t("dict.inferredas") + " " + inferredClass : "");
            JLabel typeLabel = new JLabel(typeText);
            typeLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
            typeLabel.setForeground(new Color(255, 255, 255, 200));
            header.add(typeLabel, BorderLayout.SOUTH);
        }
        root.add(header, BorderLayout.NORTH);

        // ── Body ─────────────────────────────────────────────────────────────
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(Color.WHITE);
        body.setBorder(new EmptyBorder(12, 18, 8, 18));

        if (entry == null) {
            body.add(makeSection(I18n.t("dict.notfound.title"),
                I18n.t("dict.notfound.body", nodeName), new Color(0x546E7A)));
        } else {
            // Aviso de inferencia parcial (nombre no estándar de fabricante)
            if (isInferred) {
                JPanel inferBanner = new JPanel(new BorderLayout(8, 0));
                inferBanner.setBackground(new Color(0xFFF8E1));
                inferBanner.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 4, 0, 0, new Color(0xFFB300)),
                    new EmptyBorder(7, 10, 7, 10)));
                inferBanner.setAlignmentX(Component.LEFT_ALIGNMENT);
                inferBanner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
                JLabel inferLbl = new JLabel(I18n.t("dict.infer.banner", nodeName, inferredClass));
                inferLbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
                inferLbl.setForeground(new Color(0x5D4037));
                inferBanner.add(inferLbl, BorderLayout.CENTER);
                body.add(inferBanner);
                body.add(Box.createVerticalStrut(10));
            }
            // Nombres
            JPanel namesPanel = new JPanel(new GridLayout(1, 2, 10, 0));
            namesPanel.setOpaque(false);
            namesPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            namesPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
            namesPanel.add(makeField("Nombre (ES)", entry.fullNameES, headerColor));
            namesPanel.add(makeField("Name (EN)",   entry.fullNameEN, headerColor));
            body.add(namesPanel);
            body.add(Box.createVerticalStrut(10));

            // Descripción
            body.add(makeSection(I18n.t("dict.description"), entry.localizedDescription(), headerColor));
            body.add(Box.createVerticalStrut(8));

            // Ejemplo
            if (entry.example != null) {
                body.add(makeSection(I18n.t("dict.example"), entry.localizedExample(), new Color(0x1B5E20)));
                body.add(Box.createVerticalStrut(8));
            }

            // Norma
            body.add(makeField(I18n.t("dict.standard"), entry.standard, new Color(0x4A148C)));
        }

        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(12);
        root.add(scroll, BorderLayout.CENTER);

        // ── Footer ───────────────────────────────────────────────────────────
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(new Color(0xF5F5F5));
        footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xDDD)));

        JPanel footerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 8));
        footerRight.setOpaque(false);
        if (legendAction != null) {
            JButton btnLegend = new JButton(I18n.t("legend.btn"));
            btnLegend.setForeground(new Color(0, 80, 160));
            btnLegend.addActionListener(e -> legendAction.open(dialog));
            footerRight.add(btnLegend);
        }
        JButton btnClose = new JButton(I18n.t("btn.close"));
        btnClose.addActionListener(e -> dialog.dispose());
        footerRight.add(btnClose);
        footer.add(footerRight, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        dialog.getRootPane().setDefaultButton(btnClose);
        dialog.setVisible(true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers de UI
    // ─────────────────────────────────────────────────────────────────────────
    private static JPanel makeSection(String title, String content, Color accentColor) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JLabel lbl = new JLabel(title.toUpperCase());
        lbl.setFont(new Font("SansSerif", Font.BOLD, 10));
        lbl.setForeground(accentColor);
        p.add(lbl, BorderLayout.NORTH);

        JTextArea ta = new JTextArea(content.startsWith("<html>") ?
            content.replaceAll("<[^>]+>", "").replace("&nbsp;", " ") : content);
        ta.setFont(new Font("SansSerif", Font.PLAIN, 13));
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setEditable(false);
        ta.setBackground(new Color(0xFAFAFA));
        ta.setBorder(new EmptyBorder(6, 8, 6, 8));
        ta.setOpaque(true);
        p.add(ta, BorderLayout.CENTER);

        return p;
    }

    private static JPanel makeField(String title, String content, Color accentColor) {
        JPanel p = new JPanel(new BorderLayout(0, 2));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbl = new JLabel(title.toUpperCase());
        lbl.setFont(new Font("SansSerif", Font.BOLD, 10));
        lbl.setForeground(accentColor);
        p.add(lbl, BorderLayout.NORTH);

        JLabel val = new JLabel("<html>" + content + "</html>");
        val.setFont(new Font("SansSerif", Font.PLAIN, 13));
        p.add(val, BorderLayout.CENTER);

        return p;
    }
}
