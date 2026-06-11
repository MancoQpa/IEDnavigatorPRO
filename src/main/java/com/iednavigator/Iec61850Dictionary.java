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
class Iec61850Dictionary {

    // ─────────────────────────────────────────────────────────────────────────
    //  Tipos de entrada
    // ─────────────────────────────────────────────────────────────────────────
    enum EntryType {
        LOGICAL_NODE  ("Nodo Lógico (LN)",          new Color(0x1565C0), "LN"),
        CDC           ("Clase de Dato Común (CDC)",  new Color(0x2E7D32), "CDC"),
        FC            ("Restricción Funcional (FC)",  new Color(0x6A1B9A), "FC"),
        DATA_OBJECT   ("Objeto de Dato (DO)",         new Color(0xE65100), "DO"),
        DATA_ATTRIBUTE("Atributo de Dato (DA)",       new Color(0x37474F), "DA"),
        LOGICAL_DEVICE("Dispositivo Lógico (LD)",     new Color(0x00695C), "LD"),
        SPECIAL       ("Elemento especial",           new Color(0x78909C), "??");

        final String label;
        final Color  color;
        final String badge;
        EntryType(String label, Color color, String badge) {
            this.label = label; this.color = color; this.badge = badge;
        }
    }

    static class Entry {
        final EntryType type;
        final String    fullNameES;
        final String    fullNameEN;
        final String    description;
        final String    standard;
        final String    example;   // puede ser null

        Entry(EntryType type, String fullNameES, String fullNameEN,
              String description, String standard, String example) {
            this.type = type; this.fullNameES = fullNameES; this.fullNameEN = fullNameEN;
            this.description = description; this.standard = standard; this.example = example;
        }
        Entry(EntryType type, String fullNameES, String fullNameEN,
              String description, String standard) {
            this(type, fullNameES, fullNameEN, description, standard, null);
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

    // ─────────────────────────────────────────────────────────────────────────
    //  Diálogo de ayuda
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Muestra el diálogo educativo IEC 61850 para el nombre de nodo dado.
     * Si no hay entrada en el diccionario muestra un mensaje genérico.
     */
    static void showInfoDialog(Component parent, String nodeName) {
        Entry entry = lookup(nodeName);

        // Detectar si el match fue por inferencia parcial
        // (el nombre sin dígitos no coincide directamente con la clase inferida)
        String inferredClass = inferLnClass(nodeName);
        String stripped = nodeName.replaceAll("\\d+$", "").toUpperCase();
        boolean isInferred = entry != null
                && inferredClass != null
                && !stripped.equals(inferredClass);

        JDialog dialog = new JDialog(
            (Frame) SwingUtilities.getWindowAncestor(parent),
            "IEC 61850 — Descripción del Elemento", true);
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
            String typeText = entry.type.label
                + (isInferred ? "  ·  inferido como " + inferredClass : "");
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
            body.add(makeSection("Elemento no encontrado en el diccionario",
                "<html>El elemento <b>" + nodeName + "</b> no tiene una descripción " +
                "específica en esta versión del diccionario IEC 61850.<br><br>" +
                "Puede ser un nombre definido por el fabricante, una extensión propietaria, " +
                "o una abreviatura interna del IED.<br><br>" +
                "Consulte el manual técnico del equipo o el archivo SCL/CID " +
                "para más detalles.</html>", new Color(0x546E7A)));
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
                JLabel inferLbl = new JLabel(
                    "<html><b>" + nodeName + "</b> es un nombre de fabricante. "
                    + "Clase IEC 61850 inferida por coincidencia parcial: "
                    + "<b>" + inferredClass + "</b></html>");
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
            body.add(makeSection("Descripción", entry.description, headerColor));
            body.add(Box.createVerticalStrut(8));

            // Ejemplo
            if (entry.example != null) {
                body.add(makeSection("Ejemplo de uso", entry.example, new Color(0x1B5E20)));
                body.add(Box.createVerticalStrut(8));
            }

            // Norma
            body.add(makeField("Referencia normativa", entry.standard, new Color(0x4A148C)));
        }

        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(12);
        root.add(scroll, BorderLayout.CENTER);

        // ── Footer ───────────────────────────────────────────────────────────
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 8));
        footer.setBackground(new Color(0xF5F5F5));
        footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xDDD)));
        JButton btnClose = new JButton("Cerrar");
        btnClose.addActionListener(e -> dialog.dispose());
        footer.add(btnClose);
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
