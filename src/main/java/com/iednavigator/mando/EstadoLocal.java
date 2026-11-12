package com.iednavigator.mando;


import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.BdaBoolean;
import com.beanit.iec61850bean.BdaDoubleBitPos;
import com.beanit.iec61850bean.BdaInt16;
import com.beanit.iec61850bean.BdaInt32;
import com.beanit.iec61850bean.BdaInt8;
import com.beanit.iec61850bean.BdaQuality;
import com.beanit.iec61850bean.BdaTimestamp;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.ModelNode;
import com.beanit.iec61850bean.ServerModel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maniobra local: la app hace de "proceso".
 *
 * Un IED real no inventa la posicion del aparato: la lee de los contactos
 * auxiliares del interruptor (los positivos de 110 Vcc). Este simulador corre
 * en un telefono y no tiene ese cableado, asi que la posicion y sus banderas
 * las tiene que definir la propia aplicacion. Sin eso, la prueba de control no
 * significa nada: un Pos en intermediate-state con calidad sin definir no es
 * operable ni en un equipo de verdad.
 *
 * Esta clase cubre dos cosas:
 *
 *   1. normalizar()  - deja un estado inicial VALIDO al arrancar el servidor:
 *                      ninguna posicion en intermediate/bad, calidad good,
 *                      estampa de tiempo, sin seleccion viva, y las banderas
 *                      que habilitan la maniobra puestas en un valor coherente.
 *   2. maniobrar()   - el equivalente a abrir o cerrar el aparato a pie de
 *                      equipo. NO pasa por las reglas de control (ctlModel,
 *                      SBO, enclavamiento) a proposito: es una accion fisica,
 *                      no un mando. Sirve para preparar el escenario antes de
 *                      probar el control desde el cliente.
 */
public final class EstadoLocal {

    private static final String TAG = "EstadoLocal";

    private EstadoLocal() { }

    /** Un objeto de posicion que se puede maniobrar a pie de equipo. */
    public static final class Objeto {
        public final String ref;        // "LD/CSWI1.Pos"
        public final String ld;         // "LD"
        public final String nombre;     // "CSWI1.Pos"
        public final boolean doblePunto;
        public final int ctlModel;
        public final String estado;     // ABIERTO | CERRADO | INTERMEDIO | INVALIDO
        public final String calidad;
        /** true si es la posicion de un aparato de maniobra, no una señal suelta. */
        public final boolean esAparato;
        /** CSWI que comandan este aparato, ya agrupados. Vacio si no aplica. */
        public final String comandadoPor;

        Objeto(String ref, String ld, String nombre, boolean doblePunto,
               int ctlModel, String estado, String calidad, boolean esAparato) {
            this(ref, ld, nombre, doblePunto, ctlModel, estado, calidad, esAparato, "");
        }

        Objeto(String ref, String ld, String nombre, boolean doblePunto,
               int ctlModel, String estado, String calidad, boolean esAparato,
               String comandadoPor) {
            this.ref = ref; this.ld = ld; this.nombre = nombre;
            this.doblePunto = doblePunto; this.ctlModel = ctlModel;
            this.estado = estado; this.calidad = calidad;
            this.esAparato = esAparato;
            this.comandadoPor = comandadoPor == null ? "" : comandadoPor;
        }

        /** true si ademas del estado se puede mandar por MMS. */
        public boolean admiteMando() { return ctlModel != 0; }
    }

    // ---------------------------------------------------------------- listar

    /**
     * Objetos maniobrables: los que tienen un stVal de doble punto o booleano.
     *
     * Se incluyen tanto los controlables (con Oper) como los que solo publican
     * posicion, porque a pie de equipo se maniobra el aparato aunque el modelo
     * no exponga mando: es justamente el caso que hay que poder preparar.
     */
    public static List<Objeto> listar(ServerModel model) {
        List<Objeto> salida = new ArrayList<>();
        if (model == null) return salida;

        // getChildren() devuelve el mismo DO una vez por cada FC en la que
        // aparece, asi que sin este filtro cada objeto sale repetido.
        Set<String> yaVistos = new HashSet<>();

        for (ModelNode ld : model.getChildren()) {
            for (ModelNode ln : ld.getChildren()) {
                for (ModelNode dObj : ln.getChildren()) {
                    String ref = dObj.getReference().toString();
                    if (!yaVistos.add(ref)) continue;
                    ModelNode st = model.findModelNode(ref + ".stVal", Fc.ST);
                    if (!(st instanceof BdaDoubleBitPos) && !(st instanceof BdaBoolean)) continue;
                    if (!esManiobrable(model, ref, st)) continue;

                    String ldName = ld.getReference().toString();
                    String corto = ref.startsWith(ldName + "/")
                            ? ref.substring(ldName.length() + 1) : ref;

                    salida.add(new Objeto(ref, ldName, corto,
                            st instanceof BdaDoubleBitPos,
                            leerCtlModel(model, ref),
                            describirEstado(st),
                            describirCalidad(model, ref),
                            esAparatoDeManiobra(ln.getName(), corto)));
                }
            }
        }
        return salida;
    }

    /** El nombre del LN dentro de una referencia "LD/LNnombre.DO". */
    private static String lnDe(String ref) {
        int barra = ref.indexOf('/');
        int punto = ref.indexOf('.', barra + 1);
        return (barra < 0 || punto < 0) ? "" : ref.substring(barra + 1, punto);
    }

    /**
     * Un renglon por EQUIPO FISICO, no por nodo logico.
     *
     * El mismo interruptor aparecia cinco veces: su XCBR mas los cuatro CSWI que
     * lo comandan (CBAUTO, CBDEAD, CBNOCHK, CBSYNC). Con nueve seccionadores
     * duplicados igual, la lista daba veinticuatro renglones para tres aparatos.
     *
     * Se agrupa con posicionesVinculadas(), que ya sabe que CSWI manda que
     * aparato. El renglon representa al XCBR/XSWI -- que es la posicion fisica
     * real -- pero toma el ctlModel del CSWI que si acepta mando, porque el
     * aparato en si suele venir como status-only y mostrar eso al lado de dos
     * botones era contradictorio.
     */
    public static List<Objeto> listarAparatos(ServerModel model) {
        List<Objeto> todos = listar(model);
        Map<String, Objeto> porRef = new LinkedHashMap<>();
        for (Objeto o : todos) porRef.put(o.ref, o);

        // aparatoRef -> nombres de los CSWI que lo comandan
        Map<String, List<String>> grupos = new LinkedHashMap<>();
        for (Objeto o : todos) {
            if (!o.esAparato) continue;
            String clase = ControlPolicy.classOf(lnDe(o.ref));
            String aparatoRef = o.ref;
            if ("CSWI".equals(clase)) {
                for (String r : posicionesVinculadas(model, o.ref)) {
                    String c = ControlPolicy.classOf(lnDe(r));
                    if (("XCBR".equals(c) || "XSWI".equals(c)) && porRef.containsKey(r)) {
                        aparatoRef = r;   // el aparato manda sobre el controlador
                        break;
                    }
                }
            }
            List<String> ctrls = grupos.get(aparatoRef);
            if (ctrls == null) { ctrls = new ArrayList<>(); grupos.put(aparatoRef, ctrls); }
            if ("CSWI".equals(clase)) ctrls.add(lnDe(o.ref));
        }

        List<Objeto> salida = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : grupos.entrySet()) {
            Objeto base = porRef.get(e.getKey());
            if (base == null) continue;
            // El ctlModel util es el del controlador, no el del aparato.
            int ctl = base.ctlModel;
            for (String cswi : e.getValue()) {
                Objeto c = porRef.get(base.ld + "/" + cswi + ".Pos");
                if (c != null && c.ctlModel > ctl) ctl = c.ctlModel;
            }
            StringBuilder por = new StringBuilder();
            for (String cswi : e.getValue()) {
                if (por.length() > 0) por.append(", ");
                por.append(cswi);
            }
            salida.add(new Objeto(base.ref, base.ld, base.nombre, base.doblePunto,
                    ctl, base.estado, base.calidad, true, por.toString()));
        }
        // Las señales sueltas se conservan para el interruptor "ver todas".
        for (Objeto o : todos) if (!o.esAparato) salida.add(o);
        return salida;
    }

    /**
     * Distingue el aparato de maniobra de la señal suelta.
     *
     * Un archivo real trae decenas de booleanos y dobles puntos -- CmdBlk,
     * LocSta, PosA/PosB/PosC por polo -- que entran igual por el filtro de tipo
     * y sepultan lo que el usuario vino a buscar. En un CID con un interruptor y
     * dos seccionadores, la lista salia con mas de veinte renglones.
     *
     * El aparato es el DO llamado exactamente "Pos" dentro de un nodo logico de
     * conmutacion o de su control: XCBR, XSWI o CSWI. PosA/PosB/PosC son las
     * posiciones por polo, utiles pero secundarias, y quedan detras del
     * interruptor "ver todas".
     */
    private static boolean esAparatoDeManiobra(String lnNombre, String doCorto) {
        if (lnNombre == null || doCorto == null) return false;
        if (!doCorto.endsWith(".Pos")) return false;
        // prefijo + clase + instancia: se quita la instancia y se miran las 4 letras
        String sinInst = lnNombre.replaceAll("[0-9]+$", "");
        if (sinInst.length() < 4) return false;
        String clase = sinInst.substring(sinInst.length() - 4).toUpperCase();
        return "XCBR".equals(clase) || "XSWI".equals(clase) || "CSWI".equals(clase);
    }

    /**
     * Un objeto interesa si es controlable (tiene Oper) o si es una posicion de
     * aparato. Lo segundo se reconoce por el nombre del DO, no por la clase del
     * LN: en los archivos reales la posicion aparece tanto en XCBR/XSWI como en
     * CSWI, y a veces solo en uno de ellos.
     */
    private static boolean esManiobrable(ServerModel model, String ref, ModelNode st) {
        if (model.findModelNode(ref + ".Oper.ctlVal", Fc.CO) != null) return true;
        if (st instanceof BdaDoubleBitPos) return true;   // doble punto = posicion
        String nombre = ref.substring(ref.lastIndexOf('.') + 1);
        return "Pos".equals(nombre);
    }

    // ------------------------------------------------------------- maniobrar

    /**
     * Abre o cierra el aparato como si se lo maniobrara a pie de equipo.
     *
     * @return los atributos que cambiaron, para publicarlos con setValues()
     */
    public static List<BasicDataAttribute> maniobrar(ServerModel model, String ref,
                                                     boolean cerrar) {
        List<BasicDataAttribute> cambiados = new ArrayList<>();
        if (model == null || ref == null) return cambiados;

        ModelNode st = model.findModelNode(ref + ".stVal", Fc.ST);
        if (st instanceof BdaDoubleBitPos) {
            BdaDoubleBitPos p = (BdaDoubleBitPos) copiaDe(st);
            p.setDoubleBitPos(cerrar
                    ? BdaDoubleBitPos.DoubleBitPos.ON
                    : BdaDoubleBitPos.DoubleBitPos.OFF);
            cambiados.add(p);
        } else if (st instanceof BdaBoolean) {
            BdaBoolean b = (BdaBoolean) copiaDe(st);
            b.setValue(cerrar);
            cambiados.add(b);
        } else {
            Log.w(TAG, "maniobrar: " + ref + " no tiene un stVal maniobrable");
            return cambiados;
        }

        calidadBuena(model, ref, cambiados, true);
        estampar(model, ref, cambiados, true);

        // El aparato es uno solo: si se lo maniobra, lo tienen que ver tambien
        // los LN que lo comandan o lo representan.
        for (String vinculado : posicionesVinculadas(model, ref)) {
            aplicarPosicion(model, vinculado, cerrar, cambiados);
        }

        // Se registra cuantas posiciones vinculadas se movieron, igual que hace
        // ControlEngine con el mando por MMS. Sin esto, un fallo del emparejamiento
        // por nombre era invisible en el log y solo se notaba en la pantalla.
        List<String> v = posicionesVinculadas(model, ref);
        Log.i(TAG, "Maniobra local: " + ref + " -> " + (cerrar ? "CERRADO" : "ABIERTO")
                + (v.isEmpty() ? " (sin vinculadas)" : " (y " + v.size() + " vinculada(s): " + v + ")"));
        return cambiados;
    }

    // ------------------------------------------------- vinculo controlador/aparato

    /**
     * Posiciones que tienen que moverse junto con esta.
     *
     * En IEC 61850-7-4 el mando y el aparato son LN distintos: el cliente opera
     * un CSWI (controlador de maniobra) y el que representa la posicion fisica
     * del equipo es un XCBR (interruptor) o un XSWI (seccionador). En un IED
     * real, operar el CSWI mueve el aparato y TODOS los CSWI que lo comandan
     * reflejan la nueva posicion.
     *
     * El simulador aplicaba el mando solo al DO que lo recibia, asi que un
     * cliente veia "CONTROL OK" y la posicion del interruptor no se movia. Es
     * exactamente lo que reporto el usuario mirando CBXCBR1/Pos/stVal mientras
     * operaba CBNOCHKCSWI1.Pos.
     *
     * El vinculo no viaja por MMS: hay que deducirlo de los nombres, dentro del
     * mismo Logical Device. Verificado contra el modelo de un relé de alimentador real:
     *
     *   DSCSWI3      -> DSXSWI3     mismo prefijo y misma instancia
     *   CBNOCHKCSWI1 -> CBXCBR1     prefijo "CB" + calificador del modo de
     *                               chequeo; cuatro CSWI (CBAUTO, CBDEAD,
     *                               CBNOCHK, CBSYNC) comandan UN solo XCBR
     *
     * Por eso las reglas van de la mas estricta a la mas laxa, y si ninguna
     * resuelve se devuelve vacio: es preferible no mover nada a mover el
     * aparato equivocado.
     */
    public static List<String> posicionesVinculadas(ServerModel model, String doRef) {
        List<String> salida = new ArrayList<>();
        if (model == null || doRef == null) return salida;

        int barra = doRef.indexOf('/');
        int punto = doRef.indexOf('.', barra + 1);
        if (barra < 0 || punto < 0) return salida;
        String ld = doRef.substring(0, barra);
        String ln = doRef.substring(barra + 1, punto);
        String doNombre = doRef.substring(punto + 1);
        if (!"Pos".equals(doNombre)) return salida;   // solo posiciones de aparato

        ModelNode ldNodo = model.getChild(ld);
        if (ldNodo == null) return salida;

        String clase = ControlPolicy.classOf(ln);
        if ("CSWI".equals(clase)) {
            String aparato = aparatoDe(ldNodo, ln);
            if (aparato == null) return salida;
            salida.add(ld + "/" + aparato + ".Pos");
            for (String otro : controladoresDe(ldNodo, aparato)) {
                if (!otro.equals(ln)) salida.add(ld + "/" + otro + ".Pos");
            }
        } else if ("XCBR".equals(clase) || "XSWI".equals(clase)) {
            for (String c : controladoresDe(ldNodo, ln)) salida.add(ld + "/" + c + ".Pos");
        }
        return salida;
    }

    /** El XCBR/XSWI que comanda este CSWI, o null si no se puede decidir. */
    private static String aparatoDe(ModelNode ld, String cswi) {
        String[] p = ControlPolicy.splitLnName(cswi);
        String prefijo = p[0], inst = p[2];

        List<String> aparatos = new ArrayList<>();
        for (ModelNode ln : ld.getChildren()) {
            String c = ControlPolicy.classOf(ln.getName());
            if ("XCBR".equals(c) || "XSWI".equals(c)) aparatos.add(ln.getName());
        }
        if (aparatos.isEmpty()) return null;

        // 1) mismo prefijo y misma instancia
        for (String ap : aparatos) {
            String[] q = ControlPolicy.splitLnName(ap);
            if (q[0].equals(prefijo) && q[2].equals(inst)) return ap;
        }
        // 2) misma instancia y prefijo que sea comienzo del del CSWI (el mas largo)
        String mejor = null; int largo = -1;
        for (String ap : aparatos) {
            String[] q = ControlPolicy.splitLnName(ap);
            if (!q[2].equals(inst)) continue;
            if (!q[0].isEmpty() && prefijo.startsWith(q[0]) && q[0].length() > largo) {
                mejor = ap; largo = q[0].length();
            }
        }
        if (mejor != null) return mejor;
        // 3) si el LD tiene un solo aparato, es ese
        return (aparatos.size() == 1) ? aparatos.get(0) : null;
    }

    /** Los CSWI del LD que comandan ese aparato. */
    private static List<String> controladoresDe(ModelNode ld, String aparato) {
        List<String> salida = new ArrayList<>();
        for (ModelNode ln : ld.getChildren()) {
            if (!"CSWI".equals(ControlPolicy.classOf(ln.getName()))) continue;
            if (aparato.equals(aparatoDe(ld, ln.getName()))) salida.add(ln.getName());
        }
        return salida;
    }

    // ------------------------------------------- autoridad de mando

    /** Los tres niveles de autoridad que tiene un tablero real. */
    public enum Autoridad { LOCAL, BAHIA, ESTACION, REMOTO }

    /** Posicion de la llave del aparato, o null si el LN no declara Loc. */
    public static Boolean enLocal(ServerModel model, String doRef) {
        if (model == null || doRef == null) return null;
        int punto = doRef.lastIndexOf('.');
        if (punto < 0) return null;
        ModelNode n = model.findModelNode(doRef.substring(0, punto) + ".Loc.stVal", Fc.ST);
        return (n instanceof BdaBoolean) ? ((BdaBoolean) n).getValue() : null;
    }

    /**
     * Gira la llave selectora del aparato: LOCAL o REMOTO.
     *
     * En el equipo real es herraje, una llave de dos posiciones en la puerta del
     * tablero. No es un dato que el cliente escriba por MMS —su sentido es
     * justamente limitar lo que el cliente puede hacer—, asi que el simulador
     * tiene que ofrecer la forma de moverla, igual que ofrece maniobrar a pie de
     * equipo. En LOCAL el aparato solo acepta mandos de nivel bahia (orCat=1);
     * desde la 5.2.0, antes rechazaba todo mando por comunicacion.
     *
     * Mueve Loc, que es lo que evalua ControlPolicy, y LocKey, que es lo que un
     * cliente lee para explicarle al operador por que no puede operar. Y los
     * mueve en TODOS los nodos del aparato, porque la llave es una sola: en un
     * relé con cierre por modos eso son los cuatro CSWI de modo de chequeo mas el XCBR.
     */
    public static List<BasicDataAttribute> ponerLlave(ServerModel model, String doRef,
                                                      boolean local) {
        List<BasicDataAttribute> cambiados = new ArrayList<>();
        if (model == null || doRef == null) return cambiados;

        List<String> alcance = new ArrayList<>();
        alcance.add(doRef);
        alcance.addAll(posicionesVinculadas(model, doRef));

        Set<String> lnTocados = new java.util.LinkedHashSet<>();
        for (String ref : alcance) {
            int punto = ref.lastIndexOf('.');
            if (punto < 0) continue;
            String lnRef = ref.substring(0, punto);
            if (!lnTocados.add(lnRef)) continue;
            ponerBooleano(model, lnRef + ".Loc.stVal", Fc.ST, local, cambiados);
            ponerBooleano(model, lnRef + ".LocKey.stVal", Fc.ST, local, cambiados);
        }

        Log.i(TAG, "Llave de " + doRef + " -> " + (local ? "LOCAL" : "REMOTO")
                + ": Loc=" + local + " en " + lnTocados.size() + " nodo(s) del aparato. "
                + (local ? "Solo se opera a pie de equipo."
                         : "Vuelve a aceptar mandos por comunicacion."));
        return cambiados;
    }

    /** Autoridad vigente del Logical Device. */
    public static Autoridad autoridadDe(ServerModel model, String ldName) {
        if (model == null || ldName == null) return Autoridad.REMOTO;
        ModelNode ld = model.getChild(ldName);
        if (ld == null) return Autoridad.REMOTO;
        for (ModelNode ln : ld.getChildren()) {
            if (!esManiobra(ln.getName())) continue;
            ModelNode n = model.findModelNode(ldName + "/" + ln.getName() + ".Loc.stVal", Fc.ST);
            if (n instanceof BdaBoolean && ((BdaBoolean) n).getValue()) {
                ModelNode k = model.findModelNode(ldName + "/" + ln.getName() + ".LocKey.stVal", Fc.ST);
                boolean llave = k instanceof BdaBoolean && ((BdaBoolean) k).getValue();
                return llave ? Autoridad.LOCAL : Autoridad.BAHIA;
            }
        }
        for (String ref : refsDeLocSta(model, ldName)) {
            ModelNode ls = model.findModelNode(ref + ".stVal", Fc.ST);
            if (ls instanceof BdaBoolean && ((BdaBoolean) ls).getValue()) return Autoridad.ESTACION;
        }
        return Autoridad.REMOTO;
    }

    /**
     * Todos los LocSta del Logical Device: el del LLN0 y los de sus nodos de
     * maniobra.
     *
     * No alcanza con el del LLN0. Dos de los tres archivos del corpus con
     * aparatos operables lo declaran en el CSWI -- ver ControlPolicy.refLocSta().
     * Si el selector escribiera solo el del LLN0 en un archivo asi, moveria una
     * bandera que la regla no mira: la pantalla diria ESTACION y el equipo
     * seguiria aceptando mandos remotos.
     */
    static List<String> refsDeLocSta(ServerModel model, String ldName) {
        List<String> refs = new ArrayList<>();
        if (model == null || ldName == null) return refs;

        if (model.findModelNode(ldName + "/LLN0.LocSta.stVal", Fc.ST) instanceof BdaBoolean) {
            refs.add(ldName + "/LLN0.LocSta");
        }
        ModelNode ld = model.getChild(ldName);
        if (ld == null) return refs;
        for (ModelNode ln : ld.getChildren()) {
            if (!esManiobra(ln.getName())) continue;
            String ref = ldName + "/" + ln.getName() + ".LocSta";
            if (model.findModelNode(ref + ".stVal", Fc.ST) instanceof BdaBoolean) {
                refs.add(ref);
            }
        }
        return refs;
    }

    /**
     * Mueve la autoridad de todo el Logical Device.
     *
     * Es la jerarquia de arriba de la llave: la llave decide si ESE aparato se
     * opera a pie de maquina; esto decide desde donde se puede mandar cuando no
     * lo esta.
     *
     *   LOCAL     Loc=true y LocKey=true en todos los aparatos: selector en
     *             local, no se acepta nada por comunicacion (como un rele real).
     *   BAHIA     Loc=true y LocKey=false: el mando es de la bahia, se aceptan
     *             solo los mandos con orCat=bay-control (1 y 4).
     *             (La 5.2.0 tenia solo la variante BAHIA, con el nombre LOCAL;
     *             hasta la 5.1.1, solo la variante LOCAL.)
     *
     * Si el archivo no declara LocKey, LOCAL y BAHIA se comportan igual (BAHIA):
     * ControlPolicy no tiene con que distinguirlas.
     *   ESTACION  LocSta=true y Loc=false: se aceptan los mandos con
     *             orCat=station-control y se rechazan los de remote-control.
     *   REMOTO    ambos en false: se acepta cualquier origen.
     */
    public static List<BasicDataAttribute> ponerAutoridad(ServerModel model, String ldName,
                                                          Autoridad autoridad) {
        List<BasicDataAttribute> cambiados = new ArrayList<>();
        if (model == null || ldName == null) return cambiados;
        ModelNode ld = model.getChild(ldName);
        if (ld == null) return cambiados;

        boolean local = (autoridad == Autoridad.LOCAL || autoridad == Autoridad.BAHIA);
        boolean selectorLocal = (autoridad == Autoridad.LOCAL);
        int alcance = 0;
        for (ModelNode ln : ld.getChildren()) {
            if (!esManiobra(ln.getName())) continue;
            String lnRef = ldName + "/" + ln.getName();
            alcance++;
            ponerBooleano(model, lnRef + ".Loc.stVal", Fc.ST, local, cambiados);
            ponerBooleano(model, lnRef + ".LocKey.stVal", Fc.ST, selectorLocal, cambiados);
        }
        boolean enEstacion = (autoridad == Autoridad.ESTACION);
        List<String> locStas = refsDeLocSta(model, ldName);
        for (String ref : locStas) {
            ponerBooleano(model, ref + ".stVal", Fc.ST, enEstacion, cambiados);
        }

        // El mensaje informa el ALCANCE y el EFECTO, no cuantos valores quedaron
        // distintos: si ya estaban en la posicion pedida, un "0 aparatos" sonaria
        // a que no hizo nada cuando la autoridad si cambio.
        String donde;
        if (locStas.isEmpty()) {
            donde = "sin LocSta: este archivo no la declara en ningun nodo";
        } else if (locStas.size() == 1) {
            donde = "LocSta=" + enEstacion + " en " + nombreCorto(locStas.get(0));
        } else {
            donde = "LocSta=" + enEstacion + " en " + locStas.size() + " nodo(s)";
        }

        String efecto;
        if (selectorLocal) {
            efecto = "No se aceptan mandos por comunicacion.";
        } else if (local) {
            efecto = "Se acepta solo orCat bay-control (1, 4).";
        } else if (enEstacion && locStas.isEmpty()) {
            // Decirlo es lo unico honesto: el selector quedo en ESTACION y el
            // equipo va a seguir aceptando mandos remotos. Callarlo haria que la
            // pantalla contradijera al comportamiento.
            efecto = "OJO: sin LocSta la posicion ESTACION no tiene efecto,"
                    + " se siguen aceptando mandos remotos.";
        } else if (enEstacion) {
            efecto = "Se aceptan orCat station/bay; se rechaza remote-control.";
        } else {
            efecto = "Se aceptan mandos de cualquier origen.";
        }

        Log.i(TAG, "Autoridad de " + ldName + " -> " + autoridad
                + ": Loc=" + local + " en " + alcance + " nodo(s) de maniobra, "
                + donde + ". " + efecto);
        return cambiados;
    }

    /** "LD/CSWI1.LocSta" -> "CSWI1.LocSta", para que el mensaje no sea una parrafada. */
    private static String nombreCorto(String ref) {
        int barra = ref.indexOf('/');
        return barra > 0 ? ref.substring(barra + 1) : ref;
    }

    /** ¿Es una clase de LN de maniobra? */
    private static boolean esManiobra(String lnName) {
        String c = ControlPolicy.classOf(lnName);
        return "XCBR".equals(c) || "XSWI".equals(c) || "CSWI".equals(c);
    }

    /** Escribe la posicion (y su calidad y hora) en un DO vinculado. */
    // ------------------------------------------- respuesta del aparato y sincronismo (5.5.0)
    //
    // Lo que en un equipo real depende del proceso y no del modelo de datos: si el aparato esta
    // cableado, si su mecanismo responde, cuanto tarda en recorrer, y si hay tensiones en
    // condiciones de cierre. No hay atributo IEC 61850 que lo represente, asi que vive aca, por
    // aparato, y lo elige el operador del simulador en la ventana de maniobra.

    /** Como responde un aparato a un mando aceptado. */
    public enum Respuesta {
        /** Cambia de posicion en el acto. */
        NORMAL,
        /** No esta cableado: la posicion queda en intermedio (00); la terminacion es positiva. */
        SIN_CABLEAR,
        /** El mecanismo no actua (p. ej. bobina desconectada): no cambia; terminacion positiva. */
        TRABADO,
        /** Recorre: pasa a intermedio y llega a la posicion final despues de recorridoLentoMs. */
        LENTO
    }

    private static final Map<String, Respuesta> RESPUESTAS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<String> SINCRONISMO_FALLA =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Tiempo de recorrido de un aparato LENTO. */
    public static volatile long recorridoLentoMs = 4000;
    /** Cuanto espera un cierre con synchroChk antes de terminar en negativo. */
    public static volatile long ventanaSincronismoMs = 5000;

    public static Respuesta respuestaDe(String ref) {
        Respuesta r = ref == null ? null : RESPUESTAS.get(ref);
        return r == null ? Respuesta.NORMAL : r;
    }

    public static void ponerRespuesta(String ref, Respuesta r) {
        if (ref == null) return;
        if (r == null || r == Respuesta.NORMAL) RESPUESTAS.remove(ref);
        else RESPUESTAS.put(ref, r);
        Log.i(TAG, "Respuesta de " + ref + " -> " + respuestaDe(ref));
    }

    public static boolean sincronismoEnFalla(String ref) {
        return ref != null && SINCRONISMO_FALLA.contains(ref);
    }

    public static void ponerSincronismo(String ref, boolean falla) {
        if (ref == null) return;
        if (falla) SINCRONISMO_FALLA.add(ref); else SINCRONISMO_FALLA.remove(ref);
        Log.i(TAG, "Sincronismo de " + ref + " -> " + (falla ? "FALLA" : "OK"));
    }

    /** Al cargar otro modelo: los estados simulados del anterior no valen. */
    public static void olvidarSimulacion() {
        RESPUESTAS.clear();
        SINCRONISMO_FALLA.clear();
    }

    /** Respuesta que rige un mando sobre raiz: la del objeto o la de un aparato vinculado. */
    public static Respuesta respuestaPara(ServerModel model, String raiz) {
        Respuesta r = respuestaDe(raiz);
        if (r != Respuesta.NORMAL || model == null) return r;
        for (String v : posicionesVinculadas(model, raiz)) {
            r = respuestaDe(v);
            if (r != Respuesta.NORMAL) return r;
        }
        return Respuesta.NORMAL;
    }

    /** ¿El sincronismo esta en falla para el objeto o para un aparato vinculado? */
    public static boolean sincronismoEnFallaPara(ServerModel model, String raiz) {
        if (sincronismoEnFalla(raiz)) return true;
        if (model == null) return false;
        for (String v : posicionesVinculadas(model, raiz)) {
            if (sincronismoEnFalla(v)) return true;
        }
        return false;
    }

    /** Pone la posicion en intermedio (00), con calidad buena y hora nueva. */
    static void aplicarIntermedio(ServerModel model, String doRef, List<BasicDataAttribute> cambiados) {
        ModelNode st = model.findModelNode(doRef + ".stVal", Fc.ST);
        if (!(st instanceof BdaDoubleBitPos)) return;
        BdaDoubleBitPos p = (BdaDoubleBitPos) copiaDe(st);
        p.setDoubleBitPos(BdaDoubleBitPos.DoubleBitPos.INTERMEDIATE_STATE);
        cambiados.add(p);
        calidadBuena(model, doRef, cambiados, true);
        estampar(model, doRef, cambiados, true);
    }

    static void aplicarPosicion(ServerModel model, String doRef, boolean cerrar,
                                List<BasicDataAttribute> cambiados) {
        ModelNode st = model.findModelNode(doRef + ".stVal", Fc.ST);
        if (st instanceof BdaDoubleBitPos) {
            BdaDoubleBitPos p = (BdaDoubleBitPos) copiaDe(st);
            p.setDoubleBitPos(cerrar
                    ? BdaDoubleBitPos.DoubleBitPos.ON
                    : BdaDoubleBitPos.DoubleBitPos.OFF);
            cambiados.add(p);
        } else if (st instanceof BdaBoolean) {
            BdaBoolean b = (BdaBoolean) copiaDe(st);
            b.setValue(cerrar);
            cambiados.add(b);
        } else {
            return;
        }
        calidadBuena(model, doRef, cambiados, true);
        estampar(model, doRef, cambiados, true);
    }

    // ------------------------------------------------------------ normalizar

    /**
     * Deja todas las posiciones en un estado inicial valido.
     *
     * Que se toca y por que:
     *
     *  - stVal en intermediate-state o bad-state pasa a OFF (abierto). Son los
     *    dos valores que un aparato no puede tener en reposo: el primero es la
     *    transicion entre polos y el segundo una discordancia de contactos. Un
     *    aparato que arranca ahi no se puede operar.
     *  - q queda con validez good y sin los bits que invalidan el dato.
     *  - t recibe la hora actual: sin estampa el cliente muestra 1970.
     *  - stSeld en false, para no arrancar con una seleccion fantasma.
     *  - Loc/LocSta en false: mando a distancia HABILITADO. Con Loc=true un IED
     *    real rechaza todo lo que venga por MMS, que es justo lo contrario de
     *    lo que este simulador tiene que dejar probar.
     *  - CILO EnaOpn/EnaCls en true y RSYN Rel en true: enclavamiento y
     *    sincronismo permisivos de arranque. El que quiera probar el rechazo
     *    los pone en false desde el arbol.
     *
     * Un stVal que ya este en OFF o en ON no se toca: si el archivo describe un
     * interruptor cerrado, se respeta.
     *
     * @return cantidad de objetos normalizados
     */
    /**
     * Deja Mod/Beh/Health en "on" antes de publicar el modelo.
     *
     * Un stVal de estos enums en 0 no es un valor valido de la norma, y
     * ControlPolicy mira Beh para decidir si el nodo acepta mando: con Beh=0 se
     * rechaza todo. Los archivos del corpus suelen traerlos sin valor inicial.
     *
     * Va ANTES de crear el ServerSap, para que el primer cliente que lea ya vea
     * un modelo operable.
     *
     * El tipo entero concreto depende del rango de ordinales del EnumType:
     * SclParser elige INT8 o INT16 segun eso, asi que hay que cubrir ambos.
     * Verificado: el CID de ABB (FP1REF630) trae estos enums como INT16, y
     * asumir solo INT8 hacia que este metodo no hiciera nada en silencio.
     *
     * @return cantidad de atributos inicializados
     */
    public static int inicializarModoYSalud(ServerModel model) {
        if (model == null) return 0;

        int tocados = 0;
        for (BasicDataAttribute bda : model.getBasicDataAttributes()) {
            String ref = bda.getReference() != null ? bda.getReference().toString() : "";

            // Solo el stVal de Mod/Beh/Health, que son los enums de estado del LN
            boolean esEnumDeEstado = ref.endsWith(".Mod.stVal")
                    || ref.endsWith(".Beh.stVal")
                    || ref.endsWith(".Health.stVal");
            if (!esEnumDeEstado) continue;

            boolean estabaEnCero = false;
            if (bda instanceof BdaInt8 && ((BdaInt8) bda).getValue() == 0) {
                ((BdaInt8) bda).setValue((byte) 1);
                estabaEnCero = true;
            } else if (bda instanceof BdaInt16 && ((BdaInt16) bda).getValue() == 0) {
                ((BdaInt16) bda).setValue((short) 1);
                estabaEnCero = true;
            } else if (bda instanceof BdaInt32 && ((BdaInt32) bda).getValue() == 0) {
                ((BdaInt32) bda).setValue(1);
                estabaEnCero = true;
            }

            if (estabaEnCero) {
                tocados++;
                Log.d(TAG, "inicializarModoYSalud: " + ref + " 0 -> 1 ("
                        + bda.getClass().getSimpleName() + ")");
            }
        }

        if (tocados > 0) {
            Log.i(TAG, "inicializarModoYSalud: " + tocados
                    + " atributo(s) Mod/Beh/Health inicializados en 1");
        }
        return tocados;
    }

    public static int normalizar(ServerModel model, List<BasicDataAttribute> cambiados) {
        if (model == null) return 0;
        int tocados = 0;

        for (Objeto o : listar(model)) {
            ModelNode st = model.findModelNode(o.ref + ".stVal", Fc.ST);
            if (st instanceof BdaDoubleBitPos) {
                BdaDoubleBitPos p = (BdaDoubleBitPos) st;
                BdaDoubleBitPos.DoubleBitPos v = p.getDoubleBitPos();
                if (v == BdaDoubleBitPos.DoubleBitPos.INTERMEDIATE_STATE
                        || v == BdaDoubleBitPos.DoubleBitPos.BAD_STATE) {
                    p.setDoubleBitPos(BdaDoubleBitPos.DoubleBitPos.OFF);
                    cambiados.add(p);
                    Log.d(TAG, "normalizar: " + o.ref + " " + v + " -> OFF (abierto)");
                }
            }
            calidadBuena(model, o.ref, cambiados, false);
            estampar(model, o.ref, cambiados, false);
            ponerBooleano(model, o.ref + ".stSeld", Fc.ST, false, cambiados);
            tocados++;
        }

        // Banderas de habilitacion, que viven en otros LN del mismo LD
        int permisos = 0;
        for (ModelNode ld : model.getChildren()) {
            for (ModelNode ln : ld.getChildren()) {
                String lnRef = ln.getReference().toString();
                String clase = ControlPolicy.classOf(ln.getName());
                if ("CILO".equals(clase)) {
                    if (ponerBooleano(model, lnRef + ".EnaOpn.stVal", Fc.ST, true, cambiados)) permisos++;
                    if (ponerBooleano(model, lnRef + ".EnaCls.stVal", Fc.ST, true, cambiados)) permisos++;
                } else if ("RSYN".equals(clase)) {
                    if (ponerBooleano(model, lnRef + ".Rel.stVal", Fc.ST, true, cambiados)) permisos++;
                }
                if (ponerBooleano(model, lnRef + ".Loc.stVal", Fc.ST, false, cambiados)) permisos++;
                if (ponerBooleano(model, lnRef + ".LocSta.stVal", Fc.ST, false, cambiados)) permisos++;
            }
        }

        Log.i(TAG, "Estado inicial: " + tocados + " posicion(es) normalizada(s), "
                + permisos + " bandera(s) de habilitacion puesta(s)");
        return tocados;
    }

    // ---------------------------------------------------------------- helpers

    /** Calidad del objeto: validez good y sin bits que invaliden el dato. */
    private static void calidadBuena(ServerModel model, String ref,
                                     List<BasicDataAttribute> cambiados, boolean enCopia) {
        ModelNode q = model.findModelNode(ref + ".q", Fc.ST);
        if (!(q instanceof BdaQuality)) return;
        BdaQuality bq = enCopia ? (BdaQuality) copiaDe(q) : (BdaQuality) q;
        bq.setValidity(BdaQuality.Validity.GOOD);
        bq.setFailure(false);
        bq.setOldData(false);
        bq.setOscillatory(false);
        bq.setInconsistent(false);
        bq.setInaccurate(false);
        bq.setBadReference(false);
        bq.setOutOfRange(false);
        bq.setOverflow(false);
        bq.setOperatorBlocked(false);
        cambiados.add(bq);
    }

    /** Estampa de tiempo del objeto. Sin esto el cliente muestra 1970. */
    private static void estampar(ServerModel model, String ref,
                                 List<BasicDataAttribute> cambiados, boolean enCopia) {
        ModelNode t = model.findModelNode(ref + ".t", Fc.ST);
        if (t instanceof BdaTimestamp) {
            BdaTimestamp bt = enCopia ? (BdaTimestamp) copiaDe(t) : (BdaTimestamp) t;
            bt.setInstant(Instant.now());
            cambiados.add(bt);
        }
    }

    /**
     * Copia de trabajo. ServerSap.setValues() lee bda.mirror, que solo lo deja
     * puesto copy(); y ademas solo emite reporte si el valor de la copia difiere
     * del que tiene el modelo. Por eso se copia primero y se modifica la copia.
     * Ver el mismo comentario en ControlEngine.copiaDe().
     */
    private static BasicDataAttribute copiaDe(ModelNode n) {
        return (BasicDataAttribute) ((BasicDataAttribute) n).copy();
    }

    private static boolean ponerBooleano(ServerModel model, String ref, Fc fc,
                                         boolean valor, List<BasicDataAttribute> cambiados) {
        ModelNode n = model.findModelNode(ref, fc);
        if (!(n instanceof BdaBoolean)) return false;
        BdaBoolean b = (BdaBoolean) n;
        if (b.getValue() == valor) return false;
        b.setValue(valor);
        cambiados.add(b);
        return true;
    }

    private static int leerCtlModel(ServerModel model, String ref) {
        Long v = ControlEngine.leerEnteroDeConfig(model, ref + ".ctlModel");
        return v != null ? v.intValue() : 0;
    }

    private static String describirEstado(ModelNode st) {
        if (st instanceof BdaDoubleBitPos) {
            switch (((BdaDoubleBitPos) st).getDoubleBitPos()) {
                case OFF:  return "ABIERTO";
                case ON:   return "CERRADO";
                case BAD_STATE: return "DISCORDANTE";
                default:   return "INTERMEDIO";
            }
        }
        if (st instanceof BdaBoolean) {
            return ((BdaBoolean) st).getValue() ? "CERRADO" : "ABIERTO";
        }
        return "?";
    }

    private static String describirCalidad(ServerModel model, String ref) {
        ModelNode q = model.findModelNode(ref + ".q", Fc.ST);
        if (!(q instanceof BdaQuality)) return "sin q";
        BdaQuality bq = (BdaQuality) q;
        String v = bq.getValidity() == BdaQuality.Validity.GOOD ? "buena"
                : bq.getValidity().toString().toLowerCase();
        if (bq.isTest()) v += " (prueba)";
        if (bq.isSubstituted()) v += " (sustituida)";
        return v;
    }
}
