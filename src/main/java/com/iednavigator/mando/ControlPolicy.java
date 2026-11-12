package com.iednavigator.mando;


import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.BdaBoolean;
import com.beanit.iec61850bean.BdaCheck;
import com.beanit.iec61850bean.BdaInt16;
import com.beanit.iec61850bean.BdaInt32;
import com.beanit.iec61850bean.BdaInt8;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.ModelNode;
import com.beanit.iec61850bean.ServerModel;
import com.beanit.iec61850bean.ServiceError;

import java.util.List;

/**
 * Decide si una escritura de control (Select / Operate) debe rechazarse.
 *
 * En iec61850bean, Select/Operate/Cancel no tienen un callback propio: llegan
 * como escrituras MMS comunes sobre la estructura FC=CO, al mismo
 * ServerEventListener.write() que cualquier otra escritura. Esta clase mira ese
 * lote y aplica la politica.
 *
 * Un servidor que acepta todo no sirve para probar un cliente: solo ejercita el
 * camino feliz y nunca sus rutas de diagnostico. Aca el rechazo sale del
 * **propio modelo**, no de un interruptor artificial, asi que el operador del
 * simulador provoca los rechazos poniendo valores en el arbol, igual que
 * ocurriria en un equipo real.
 *
 * Vive aparte de IEDServerService, y sin depender de nada de Android salvo Log,
 * justamente para poder ejercitarla contra archivos CID reales fuera del
 * telefono.
 */
public final class ControlPolicy {

    private static final String TAG = "ControlPolicy";

    // Ordinales de Beh/Mod segun IEC 61850-7-3
    static final int BEH_ON = 1;
    static final int BEH_BLOCKED = 2;
    static final int BEH_TEST = 3;
    static final int BEH_TEST_BLOCKED = 4;
    static final int BEH_OFF = 5;

    private ControlPolicy() {}

    /**
     * @return el ServiceError con el que responder, o null si el control procede
     *         (o si el lote no es un control en absoluto)
     */
    public static ServiceError evaluate(ServerModel model, List<BasicDataAttribute> bdas) {
        if (model == null || bdas == null) return null;

        // Localizar el ctlVal de un Oper/SBOw dentro del lote
        BasicDataAttribute ctlVal = null;
        String controlRoot = null;   // p.ej. "IED1CTRL/CSWI1.Pos"
        for (BasicDataAttribute bda : bdas) {
            if (bda.getFc() != Fc.CO) continue;
            String ref = bda.getReference() != null ? bda.getReference().toString() : "";
            int i = ref.indexOf(".Oper.ctlVal");
            if (i < 0) i = ref.indexOf(".SBOw.ctlVal");
            if (i >= 0) {
                ctlVal = bda;
                controlRoot = ref.substring(0, i);
                break;
            }
        }
        if (ctlVal == null) return null;   // no es un control: escritura normal

        // Check y Test viajan en el mismo lote que el ctlVal
        BdaCheck check = null;
        Boolean testFlag = null;
        Integer orCat = null;   // de donde dice venir la orden (Originator, 7-3)
        for (BasicDataAttribute bda : bdas) {
            String ref = bda.getReference() != null ? bda.getReference().toString() : "";
            if (bda instanceof BdaCheck) {
                check = (BdaCheck) bda;
            } else if (bda instanceof BdaBoolean && ref.endsWith(".Test")) {
                testFlag = ((BdaBoolean) bda).getValue();
            } else if (ref.endsWith(".origin.orCat")) {
                orCat = leerEnteroDe(bda);
            }
        }

        // controlRoot = "<LD>/<LN>.<DO>" -> quedarse con "<LD>/<LN>"
        int dot = controlRoot.indexOf('.');
        String lnRef = dot > 0 ? controlRoot.substring(0, dot) : controlRoot;
        String lnName = lnRef.contains("/") ? lnRef.substring(lnRef.indexOf('/') + 1) : lnRef;

        Log.i(TAG, "Control sobre " + controlRoot + " ctlVal=" + ctlVal.getValueString()
                + " test=" + testFlag
                + " interlkChk=" + (check != null ? check.getInterlockCheck() : "n/d")
                + " synchroChk=" + (check != null ? check.getSynchrocheck() : "n/d"));

        // --- 1. Mando local: LOCAL (bloqueo total) o BAHIA (solo orCat de bahia) ---
        //
        // Con Loc=true el mando no lo tiene ni la estacion ni el centro de control.
        // Hay dos variantes, que el simulador ofrece por separado desde la 5.3.0:
        //
        //   LocKey=true  LOCAL: el selector fisico esta en local y se rechaza TODA
        //                orden por comunicaciones, venga del nivel que venga. Es lo
        //                que hacen los reles con selector local/remoto: rechazan
        //                cualquier orCat con AddCause 2.
        //   LocKey=false BAHIA: el mando es del nivel bahia; se aceptan orCat=1
        //                (bay-control) y 4 (automatic-bay) y se rechazan los demas.
        //                Una orden sin orCat se rechaza: no se sabe si viene de la
        //                bahia. Es lo que los usuarios esperaban de "local" (5.2.0).
        //
        // Va primero porque es el nivel mas restrictivo: en un SIPROTEC 5 6MD85
        // con Loc=true era el primer rechazo, antes incluso del enclavamiento.
        //
        // Se lee el Loc del LN de control (el CSWI), no el del aparato: en el
        // 6MD85 el XCBR informaba Loc=false mientras el CSWI que lo comanda
        // informaba Loc=true, y el que gobierna la autoridad es el segundo.
        Boolean loc = readBooleanStVal(model, lnRef + ".Loc");
        if (Boolean.TRUE.equals(loc)) {
            Boolean llave = readBooleanStVal(model, lnRef + ".LocKey");
            if (Boolean.TRUE.equals(llave)) {
                return new ServiceError(ServiceError.ACCESS_NOT_ALLOWED_IN_CURRENT_STATE,
                        lnName + ": LocKey=true, selector en LOCAL: se rechaza toda orden"
                                + " por comunicaciones (llego con " + nombreOrCat(orCat) + ")");
            }
            boolean deBahia = orCat != null
                    && (orCat == OR_BAY_CONTROL || orCat == OR_AUTOMATIC_BAY);
            if (!deBahia) {
                return new ServiceError(ServiceError.ACCESS_NOT_ALLOWED_IN_CURRENT_STATE,
                        lnName + ": Loc=true, el mando lo tiene la BAHIA: se acepta"
                                + " solo bay-control y la orden llego con " + nombreOrCat(orCat));
            }
        }

        // --- 1b. Autoridad a nivel estacion ---
        //
        // Loc resuelve "local contra todo lo demas", pero la autoridad tiene tres
        // niveles, no dos: con LocSta=true en el LLN0 el mando lo tiene la
        // ESTACION, asi que una orden del centro de control se rechaza y una del
        // HMI de la subestacion se acepta. Distinguirlas exige mirar de donde
        // dice venir la orden, y por eso hace falta el orCat: un booleano no
        // puede expresar una jerarquia de tres.
        //
        // Es el caso que dio un SIPROTEC 5 rechazando todo mando sin explicar por
        // que, y que hasta ahora no se podia reproducir sin tener el equipo.
        //
        // Si la orden no trae orCat no se la bloquea por jerarquia: sin saber de
        // donde viene, negar por las dudas volveria irrechazable lo que el
        // operador justamente queria probar. El Loc de arriba ya cubre el caso
        // duro.
        String refLocSta = refLocSta(model, lnRef);
        if (refLocSta != null && orCat != null) {
            boolean enEstacion = Boolean.TRUE.equals(readBooleanStVal(model, refLocSta));
            boolean vieneDeRemoto = (orCat == OR_REMOTE_CONTROL || orCat == OR_AUTOMATIC_REMOTE);
            if (enEstacion && vieneDeRemoto) {
                return new ServiceError(ServiceError.ACCESS_NOT_ALLOWED_IN_CURRENT_STATE,
                        lnName + ": LocSta=true, el mando lo tiene la ESTACION y la orden"
                                + " llego con orCat=" + nombreOrCat(orCat)
                                + " (se aceptaria station-control o bay-control)");
            }
        }

        // --- 2. Modo de comportamiento del LN ---
        Integer beh = readIntStVal(model, lnRef + ".Beh");
        if (beh != null) {
            if (beh == BEH_OFF) {
                return new ServiceError(ServiceError.ACCESS_NOT_ALLOWED_IN_CURRENT_STATE,
                        lnName + ": Beh=off, el nodo logico esta fuera de servicio");
            }
            if (beh == BEH_BLOCKED) {
                return new ServiceError(ServiceError.ACCESS_NOT_ALLOWED_IN_CURRENT_STATE,
                        lnName + ": Beh=blocked, mando bloqueado");
            }

            // --- 3. Coherencia del bit Test ---
            boolean lnEnPrueba = (beh == BEH_TEST || beh == BEH_TEST_BLOCKED);
            boolean pedidoDePrueba = Boolean.TRUE.equals(testFlag);
            if (pedidoDePrueba && !lnEnPrueba) {
                return new ServiceError(ServiceError.PARAMETER_VALUE_INCONSISTENT,
                        lnName + ": mando con Test=true pero Beh=on (el nodo no esta en modo prueba)");
            }
            if (!pedidoDePrueba && lnEnPrueba) {
                return new ServiceError(ServiceError.PARAMETER_VALUE_INCONSISTENT,
                        lnName + ": Beh=test, solo se aceptan mandos con Test=true");
            }
        }

        boolean cerrando = ctlVal instanceof BdaBoolean && ((BdaBoolean) ctlVal).getValue();

        // --- 4. Bloqueo de maniobra del aparato ---
        //
        // BlkOpn y BlkCls son el bloqueo que alguien puso sobre el aparato: "este
        // equipo no se abre" o "no se cierra", sin importar quien lo pida ni por
        // que. A diferencia del enclavamiento, NO cuelga del campo Check: el
        // cliente no puede renunciar a el. interlkChk significa "no me verifiques
        // el enclavamiento", no "ignorame el bloqueo del aparato".
        //
        // Viven en el aparato -- XCBR o XSWI --, no en el CSWI que se opera: ni
        // SIPROTEC 4 ni SIPROTEC 5 los declaran en CSWI. Se mira igual el LN
        // operado, por si se opera el XCBR directamente o el equipo si los expone
        // ahi.
        //
        // Antes no se evaluaban, y eso era peor que no tenerlos: IEDNavigator los
        // lee y los muestra como condiciones de mando en su ventana, asi que la
        // herramienta enseniaba que una senial bloquea cuando en realidad no hacia
        // nada. Verificado el 2026-08-31: con CBXCBR1.BlkCls=true el interruptor
        // cerraba igual.
        String bloqueoDa = cerrando ? ".BlkCls" : ".BlkOpn";
        for (String duenio : new String[]{lnRef,
                findLnByClass(model, lnRef, "XCBR"),
                findLnByClass(model, lnRef, "XSWI")}) {
            if (duenio == null) continue;
            Boolean bloqueado = readBooleanStVal(model, duenio + bloqueoDa);
            if (Boolean.TRUE.equals(bloqueado)) {
                String duenioName = duenio.substring(duenio.indexOf('/') + 1);
                return new ServiceError(ServiceError.ACCESS_VIOLATION,
                        lnName + ": " + duenioName + bloqueoDa + "=true, la maniobra de "
                                + (cerrando ? "cierre" : "apertura")
                                + " esta bloqueada en el aparato");
            }
        }

        // --- 4b. Bloqueos de proceso del interruptor: gas y mecanismo ---
        //
        // Los provoca el instructor (EstadoLocal): SF6 en bloqueo impide abrir y cerrar; el
        // resorte descargado impide cerrar. Un relé real los rechaza por el proceso
        // (AddCause 9, blocked-by-process), igual que un bloqueo del aparato.
        if (controlRoot != null) {
            if (EstadoLocal.sf6Para(model, controlRoot) == EstadoLocal.Sf6.BLOQUEO) {
                return new ServiceError(ServiceError.ACCESS_VIOLATION,
                        lnName + ": presion de SF6 baja, maniobra bloqueada (bloqueo de proceso)");
            }
            if (cerrando && EstadoLocal.resorteDescargadoPara(model, controlRoot)) {
                return new ServiceError(ServiceError.ACCESS_VIOLATION,
                        lnName + ": resorte descargado, el cierre no esta disponible (bloqueo de proceso)");
            }
        }

        // --- 5. Enclavamiento, solo si el cliente lo pidio ---
        //
        // El bit interlkChk significa "servidor, verifica el enclavamiento". Si
        // el cliente NO lo pide, esta salteando el enclavamiento a proposito y
        // se le permite: asi el campo Check del cliente cambia el resultado de
        // verdad, que es justamente lo que se quiere poder probar.
        if (check != null && check.getInterlockCheck()) {
            String cilo = findLnByClass(model, lnRef, "CILO");
            if (cilo == null) {
                // Se deja pasar, pero se avisa: el cliente pidio verificar
                // enclavamiento y no hay CILO que le corresponda a este LN.
                Log.w(TAG, lnName + ": se pidio verificar enclavamiento pero no hay"
                        + " ningun CILO emparejado; la comprobacion se omite");
            }
            if (cilo != null) {
                String enable = cilo + (cerrando ? ".EnaCls" : ".EnaOpn");
                Boolean permitido = readBooleanStVal(model, enable);
                if (Boolean.FALSE.equals(permitido)) {
                    String ciloName = cilo.substring(cilo.indexOf('/') + 1);
                    return new ServiceError(ServiceError.ACCESS_VIOLATION,
                            lnName + ": enclavamiento " + ciloName + "."
                                    + (cerrando ? "EnaCls" : "EnaOpn")
                                    + "=false, la maniobra de "
                                    + (cerrando ? "cierre" : "apertura") + " esta bloqueada");
                }
            }
        }

        // --- 6. Synchrocheck ---
        //
        // Desde la 5.5.0 no se rechaza aca. Un rele real acepta el Oper, espera una ventana a que
        // se den las condiciones y, si no se dan, termina el mando en negativo con AddCause 11
        // (blocked-by-synchrocheck). Lo hace ControlEngine, con rsynSinPermiso() y el estado de
        // sincronismo que el operador elige por aparato.

        return null;   // el control procede
    }

    /**
     * RSYN del LN de control que NO da permiso de cierre (Rel=false), o null. RSYN.Rel es la
     * salida de permiso del LN de sincronismo (IEC 61850-7-4).
     */
    static String rsynSinPermiso(ServerModel model, String lnRef) {
        String rsyn = findLnByClass(model, lnRef, "RSYN");
        if (rsyn == null) return null;
        return Boolean.FALSE.equals(readBooleanStVal(model, rsyn + ".Rel")) ? rsyn : null;
    }

    /**
     * Descompone el nombre de un LN segun IEC 61850-7-1: [prefijo]CLASE[instancia].
     * La clase siempre son 4 caracteres (XCBR, CILO, CSWI, RSYN...).
     *
     * Ej: "SCILO11" -> {"S","CILO","11"}   "CBCILO1" -> {"CB","CILO","1"}
     *     "AscRSYN1" -> {"Asc","RSYN","1"} "XCBR1"   -> {"","XCBR","1"}
     *
     * @return {prefijo, clase, instancia} o null si el nombre no encaja
     */
    /**
     * Clase IEC 61850-7-4 de un LN, sin prefijo de fabricante ni instancia.
     * Ej: "SPGGIO1" -> "GGIO", "DRRDRE1" -> "RDRE", "M03_MMXU1" -> "MMXU".
     * @return la clase de 4 letras, o null si el nombre no descompone
     */
    // Categorias de origen del mando (IEC 61850-7-3, tipo Originator)
    private static final int OR_BAY_CONTROL        = 1;
    private static final int OR_STATION_CONTROL    = 2;
    private static final int OR_REMOTE_CONTROL     = 3;
    private static final int OR_AUTOMATIC_BAY      = 4;
    private static final int OR_AUTOMATIC_STATION  = 5;
    private static final int OR_AUTOMATIC_REMOTE   = 6;

    /** Nombre legible del origen, para que el rechazo diga que habria que mandar. */
    static String nombreOrCat(Integer orCat) {
        if (orCat == null) return "sin origen";
        switch (orCat) {
            case OR_BAY_CONTROL:       return "bay-control";
            case OR_STATION_CONTROL:   return "station-control";
            case OR_REMOTE_CONTROL:    return "remote-control";
            case OR_AUTOMATIC_BAY:     return "automatic-bay";
            case OR_AUTOMATIC_STATION: return "automatic-station";
            case OR_AUTOMATIC_REMOTE:  return "automatic-remote";
            case 7: return "maintenance";
            case 8: return "process";
            default: return "orCat=" + orCat;
        }
    }

    /** Entero de un BDA sin asumir el ancho: el orCat viene como Int8 pero no siempre. */
    private static Integer leerEnteroDe(BasicDataAttribute bda) {
        if (bda instanceof BdaInt8)   return (int) ((BdaInt8) bda).getValue();
        if (bda instanceof BdaInt16)  return (int) ((BdaInt16) bda).getValue();
        if (bda instanceof BdaInt32)  return ((BdaInt32) bda).getValue();
        return null;
    }

    public static String classOf(String lnName) {
        String[] p = splitLnName(lnName);
        return p == null ? null : p[1];
    }

    /**
     * Grupo IEC 61850-7-4 de un LN: la primera letra de su CLASE, no de su
     * nombre. La diferencia importa porque casi todos los fabricantes prefijan:
     * en "SPGGIO1" el grupo es G (GGIO, generico), no S; en "DRRDRE1" es R
     * (RDRE, registrador de perturbaciones), no D.
     *
     * @return la letra del grupo, o 0 si no se puede determinar
     */
    public static char groupOf(String lnName) {
        if (lnName == null || lnName.isEmpty()) return 0;
        // LLN0 es un nombre especial del estandar y no descompone en
        // prefijo+clase-de-4+instancia; su grupo es L.
        if (lnName.toUpperCase().startsWith("LLN0")) return 'L';
        String cls = classOf(lnName);
        if (cls != null && !cls.isEmpty()) return Character.toUpperCase(cls.charAt(0));
        return Character.toUpperCase(lnName.charAt(0));   // ultimo recurso
    }

    static String[] splitLnName(String ln) {
        if (ln == null) return null;
        int i = ln.length();
        while (i > 0 && Character.isDigit(ln.charAt(i - 1))) i--;
        String inst = ln.substring(i);
        String rest = ln.substring(0, i);
        if (inst.isEmpty() || rest.length() < 4) return null;
        return new String[]{rest.substring(0, rest.length() - 4),
                            rest.substring(rest.length() - 4),
                            inst};
    }

    /**
     * Busca en el mismo LD el LN de una clase dada que corresponde a un LN de
     * mando, emparejando por numero de instancia (CSWI1 -> CILO1, XCBR2 -> CILO2).
     *
     * Hay que buscar por clase y no armar el nombre a mano, porque los
     * fabricantes le ponen prefijo: ABB usa SCILO1, y un SCD de subestacion real
     * usa CBCILO1 para el interruptor y DSCILO2..10 para los seccionadores.
     * Construir "CILO"+instancia no encontraba nada en esos archivos y el
     * enclavamiento se salteaba en silencio.
     *
     * Si hay varios candidatos con la misma instancia se prefiere el que
     * comparta prefijo con el LN de mando (CBXCBR1 -> CBCILO1).
     *
     * El respaldo NO cruza prefijos distintos. Antes tomaba "el primero de la
     * misma instancia" y eso emparejaba DSCSWI1 con CBCILO1: el seccionador 1
     * quedaba enclavado por el interruptor, porque el archivo numera los CILO de
     * seccionador desde 2 y no existe DSCILO1. Poner CBCILO1.EnaCls=false para
     * probar el interruptor bloqueaba tambien al seccionador, y no habia forma de
     * enclavar ese seccionador por separado.
     *
     * Se acepta el respaldo solo cuando un prefijo es prefijo del otro, y entre
     * varios candidatos gana el de prefijo mas largo. Eso mantiene los
     * emparejamientos legitimos: CBAUTOCSWI1, CBDEADCSWI1, CBNOCHKCSWI1 y
     * CBSYNCCSWI1 son cuatro nodos de mando del mismo interruptor -- verificado,
     * una maniobra se propaga a los cuatro -- y todos deben responder a CBCILO1;
     * lo mismo CSWI1 contra el SCILO1 de ABB, o DSCSWI1 contra un CILO1 sin
     * prefijo, porque el prefijo vacio es prefijo de cualquiera.
     *
     * Si ninguno de los dos prefijos contiene al otro, como DS contra CB, son
     * aparatos distintos: se devuelve null y el llamador decide, en vez de
     * aplicar el enclavamiento de otro equipo.
     *
     * @return referencia completa del LN encontrado, o null
     */
    static String findLnByClass(ServerModel model, String lnRef, String wantedClass) {
        int slash = lnRef.indexOf('/');
        if (slash < 0) return null;
        String ldName = lnRef.substring(0, slash);
        String[] ctrl = splitLnName(lnRef.substring(slash + 1));
        if (ctrl == null) return null;
        String ctrlPrefix = ctrl[0], inst = ctrl[2];

        ModelNode ld = null;
        for (ModelNode candidate : model.getChildren()) {
            if (ldName.equals(candidate.getName())) { ld = candidate; break; }
        }
        if (ld == null) return null;

        String fallback = null;
        int largoRespaldo = -1;
        for (ModelNode ln : ld.getChildren()) {
            String[] parts = splitLnName(ln.getName());
            if (parts == null) continue;
            if (!wantedClass.equals(parts[1]) || !inst.equals(parts[2])) continue;
            if (ctrlPrefix.equals(parts[0])) return ldName + "/" + ln.getName();  // prefijo exacto
            boolean mismaFamilia = ctrlPrefix.startsWith(parts[0]) || parts[0].startsWith(ctrlPrefix);
            if (mismaFamilia && parts[0].length() > largoRespaldo) {
                largoRespaldo = parts[0].length();
                fallback = ldName + "/" + ln.getName();
            }
        }
        return fallback;
    }

    /** Lee un stVal entero (INT8/16/32 segun el rango de ordinales del EnumType). */
    static Integer readIntStVal(ServerModel model, String doRef) {
        ModelNode n = model.findModelNode(doRef + ".stVal", Fc.ST);
        if (n instanceof BdaInt8)  return (int) ((BdaInt8) n).getValue();
        if (n instanceof BdaInt16) return (int) ((BdaInt16) n).getValue();
        if (n instanceof BdaInt32) return ((BdaInt32) n).getValue();
        return null;
    }

    /** Lee un stVal booleano. */
    /**
     * Donde declara este nodo la autoridad de estacion, o null si no la declara.
     *
     * Por la norma, LocSta es un DO del LLN0: la autoridad es del Logical
     * Device entero. Pero no todos los fabricantes lo ponen ahi. Medido sobre
     * el corpus, dos de los tres archivos con aparatos operables lo declaran en
     * el CSWI y no en el LLN0 -- un SIPROTEC 5 y un SCD de subestacion de otro
     * fabricante. Buscarlo solo en el LLN0 dejaba la regla de estacion sin
     * efecto sobre esos equipos: aceptaba mandos remotos que el equipo real
     * rechaza.
     *
     * Se mira primero el LN de control y despues el LLN0, exactamente el mismo
     * orden que ya se aplica al Loc, y por la misma razon: el valor que
     * gobierna es el mas especifico. Si el LN no lo declara, la autoridad del
     * LD sigue valiendo para el.
     */
    static String refLocSta(ServerModel model, String lnRef) {
        if (readBooleanStVal(model, lnRef + ".LocSta") != null) {
            return lnRef + ".LocSta";
        }
        int barra = lnRef.indexOf('/');
        if (barra <= 0) return null;
        String enLln0 = lnRef.substring(0, barra) + "/LLN0.LocSta";
        return readBooleanStVal(model, enLln0) != null ? enLln0 : null;
    }

    static Boolean readBooleanStVal(ServerModel model, String doRef) {
        ModelNode n = model.findModelNode(doRef + ".stVal", Fc.ST);
        return (n instanceof BdaBoolean) ? ((BdaBoolean) n).getValue() : null;
    }
}
