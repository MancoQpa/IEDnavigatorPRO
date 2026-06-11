package com.iednavigator;

import com.beanit.iec61850bean.Fc;

public class ListFc {
    public static void main(String[] args) {
        System.out.println("Functional Constraints disponibles:");
        for (Fc fc : Fc.values()) {
            System.out.println("  " + fc.name() + " = " + fc.toString());
        }
    }
}
