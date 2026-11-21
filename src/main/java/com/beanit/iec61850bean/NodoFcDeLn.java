package com.beanit.iec61850bean;

import com.beanit.iec61850bean.internal.mms.asn1.Data;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Lectura de todo un FC de un Logical Node ({@code LLN0$ST}, {@code XCBR1$CF}): una estructura
 * con cada Data Object de esa FC, en el orden del LN.
 *
 * <p>Clase propia (no sombrea nada de iec61850bean). La biblioteca contesta el tipo de
 * {@code LN$FC} (GetVariableAccessAttributes, con {@code LogicalNode.getChildren(fc)}) pero no su
 * valor: {@code ServerModel.getNodeFromVariableDef} tiraba "invalid mms item id" y el pedido
 * entero fallaba. La herramienta comercial lee así cada FC de cada LN al descubrir un IED: contra un
 * CID real eran unos 1.700 errores por descubrimiento, y el cliente terminaba leyendo de a un
 * atributo (2026-11-15). Con esta clase la lectura devuelve los mismos DO que describe el tipo.
 *
 * <p>Los DO se guardan en {@code children} sin cambiarles el padre: el nodo es de usar y tirar,
 * no forma parte del modelo. Solo se lee; escribir un FC entero se rechaza.
 */
final class NodoFcDeLn extends FcModelNode {

    private NodoFcDeLn(LogicalNode ln, Fc fc, List<FcDataObject> dos) {
        this.objectReference = new ObjectReference(ln.getReference() + "." + fc);
        this.fc = fc;
        this.children = new LinkedHashMap<>();
        for (FcDataObject d : dos) children.put(d.getName(), d);
    }

    /** El nodo para leer {@code ln$fc}, o null si el LN no tiene Data Objects en esa FC. */
    static NodoFcDeLn de(LogicalNode ln, Fc fc) {
        List<FcDataObject> dos = ln.getChildren(fc);
        if (dos == null || dos.isEmpty()) return null;
        return new NodoFcDeLn(ln, fc, dos);
    }

    @Override
    Data getMmsDataObj() {
        Data.Structure estructura = new Data.Structure();
        List<Data> datos = estructura.getData();
        for (ModelNode d : children.values()) datos.add(d.getMmsDataObj());
        Data data = new Data();
        data.setStructure(estructura);
        return data;
    }

    @Override
    void setValueFromMmsDataObj(Data data) throws ServiceError {
        throw new ServiceError(ServiceError.ACCESS_VIOLATION, "no se escribe un FC entero de un LN");
    }

    @Override
    public ModelNode copy() {
        throw new UnsupportedOperationException("NodoFcDeLn no se copia: es una vista de lectura");
    }
}
