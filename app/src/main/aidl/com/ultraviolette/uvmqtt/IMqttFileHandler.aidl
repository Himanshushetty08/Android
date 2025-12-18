package com.ultraviolette.uvmqtt;

interface IMqttFileHandler {
    void uploadFile(String file_name);
    void uploadCommand(String command);
    void imei(String imei);
}