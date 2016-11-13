/*
 * Copyright 2016 gideon.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.legrange.bridge;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import me.legrange.bridge.mqtt.MqttConnector;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.legrange.bridge.modbus.ModbusReader;
import me.legrange.bridge.config.Configuration;
import me.legrange.bridge.config.ConfigurationException;
import me.legrange.bridge.config.Register;
import me.legrange.bridge.modbus.ModbusListener;
import me.legrange.bridge.modbus.ModbusReaderException;
import me.legrange.bridge.modbus.ModbusRegister;
import me.legrange.bridge.mqtt.MqttListener;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import net.objecthunter.exp4j.ValidationResult;

/**
 *
 * @since 1.0
 * @author Gideon le Grange https://github.com/GideonLeGrange
 */
public class WattNodeService {

    public static final String COMMAND = "wncmd";

    public static void main(String[] args) throws Exception {
        WattNodeService s = new WattNodeService();
        if (args.length != 1) {
            System.out.println("Confiugration file name required");
            System.exit(-1);
        }
        try {
            s.configure(args[0]);
            s.start();
            s.run();
        } catch (ConfigurationException ex) {
            error("Configuration error: " + ex.getMessage(), ex);
        } catch (ModbusReaderException ex) {
            error("Error connecting to Modbus: " + ex.getMessage(), ex);
        }
    }

    public void submit(Runnable task) {
        pool.submit(task);
    }

    public static void debug(String fmt, Object... args) {
        logger.finest(String.format(fmt, args));
    }

    public static void info(String fmt, Object... args) {
        logger.info(String.format(fmt, args));
    }

    public static void warn(String fmt, Object... args) {
        logger.warning(String.format(fmt, args));
    }

    public static void error(String msg, Throwable ex) {
        logger.log(Level.SEVERE, msg, ex);
    }

    public String getName() {
        return "wattnode-mqtt";
    }

    /**
     * Default private constructor
     */
    private WattNodeService() {
    }

    /**
     * Configure the application.
     *
     * @param fileName The configuration file to parse.
     * @throws ConfigurationException Indicates there is a error in the
     * configuration.
     */
    private void configure(String fileName) throws ConfigurationException {
        this.config = Configuration.readConfiguration(fileName);
    }

    /**
     * Start the service.
     *
     * @throws ServiceException
     */
    private void start() throws ServiceException {

        running = true;
        startMqtt();
        startModbus();
        info("service started");
    }

    private void stop() {
        stopMqtt();
        stopModbus();
        running = false;
        synchronized (this) {
            notify();
        }
        info("service stopped");
    }

    /**
     * Connect to the MQTT broker
     */
    private void startMqtt() {
        mqtt = new MqttConnector(String.format("tcp://%s:%d", config.getMqtt().getBroker().getHost(), config.getMqtt().getBroker().getPort()), this);
        mqtt.addListener(config.getMqtt().getCommandTopic(), new MqttListener() {
            @Override
            public void received(String topic, String msg) {
                        switch (msg) {
                            case "quit":
                                stop();
                        }
                    // FIX ME: Add cases here to do:
                    // -- modbus register writes
                    // -- service commands (shutdown, reset modem, reset mqtt)
            }
        });
        mqtt.start();
    }

    private void startModbus() throws ModbusReaderException {
        mbus = new ModbusReader(config.getModbus().getSerial().getPort(),
                config.getModbus().getSerial().getSpeed(),
                config.getModbus().getDeviceId(),
                config.getModbus().isZeroBased());
        mbus.addListener(new ModbusListener() {

            @Override
            public void received(ModbusRegister reg, byte bytes[]) {
                double val = ModbusRegister.decode(reg, bytes);
                mqtt.publish(config.getMqtt().getDataTopic() + "/" + reg.getName(), Double.toString(val));
            }

            @Override
            public void error(Throwable e) {
                WattNodeService.error(e.getMessage(), e);
            }
        });
        mbus.setPollInterval(config.getModbus().getPollInterval());
        mbus.start();

    }

    private void stopMqtt() {
        mqtt.stop();
    }

    private void stopModbus() {
        mbus.stop();
    }

    private void run() {
        info("service running");
        for (Register reg : config.getRegisters()) {
            mbus.addRegister(makeRegister(reg));
            debug("reg: " + reg.getName());
        }
        while (running) {
            try {
                synchronized (this) {
                    wait();
                }
            } catch (InterruptedException ex) {
            }
        }
        info("service stopping");
    }

    private ModbusRegister makeRegister(final Register reg) {
        return new ModbusRegister() {
            @Override
            public String getName() {
                return reg.getName();
            }

            @Override
            public int getAddress() {
                return reg.getAddress();
            }

            @Override
            public int getLength() {
                return reg.getLength();
            }

            @Override
            public Expression getTransform() {
                Expression transform = new ExpressionBuilder(reg.getTransform()).variables("_").build().setVariable("_", 0);
                ValidationResult val = transform.validate();
                if (!val.isValid()) {
                    throw new RuntimeException(String.format("Invalid transform '%s': %s", reg.getTransform(), val.getErrors()));
                }
                return transform;
            }

            @Override
            public ModbusRegister.Type getType() {
                switch (reg.getType()) {
                    case "float":
                        return Type.FLOAT;
                    case "int":
                        return Type.INT;
                    default:
                        throw new RuntimeException("Unknown register type '" + reg.getType() + "'");
                }
            }
        };
    }

    private boolean running;
    private MqttConnector mqtt;
    private ModbusReader mbus;
    private Configuration config;
    private final ExecutorService pool = ForkJoinPool.commonPool();
    private static final Logger logger = Logger.getLogger(WattNodeService.class.getName());

}