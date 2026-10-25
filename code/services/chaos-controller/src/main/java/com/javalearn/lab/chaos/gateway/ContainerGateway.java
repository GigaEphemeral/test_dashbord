package com.javalearn.lab.chaos.gateway;

import com.javalearn.lab.chaos.model.ContainerView;

import java.util.List;

public interface ContainerGateway {

    List<ContainerView> listLabContainers();

    String tailLogs(String containerName, int tail);
}
