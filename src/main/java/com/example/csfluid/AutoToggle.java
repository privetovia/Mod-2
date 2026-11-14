package com.example.csfluid;

/** Интерфейс, который миксин добавляет двигателю: включён ли у него авто-режим. */
public interface AutoToggle {
    boolean csfIsAuto();
    void csfSetAuto(boolean value);
}
