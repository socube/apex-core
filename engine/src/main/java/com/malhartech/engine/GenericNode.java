/*
 *  Copyright (c) 2012 Malhar, Inc.
 *  All Rights Reserved.
 */
package com.malhartech.engine;

import com.malhartech.api.IdleTimeHandler;
import com.malhartech.api.Operator;
import com.malhartech.api.Operator.InputPort;
import com.malhartech.api.Sink;
import com.malhartech.engine.OperatorStats.PortStats;
import com.malhartech.util.CircularBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import org.apache.commons.lang.UnhandledException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// inflight changes to the port connections should be captured.
/**
 *
 * The base class for node implementation<p>
 * <br>
 * Implements the base interface {@link com.malhartech.engine.Node}<br>
 * <br>
 * This is the basic functional block of the DAG. It is responsible for the following<br>
 * It emits and consumes tuples<br>
 * Upon window boundary it does house cleaning, state sync up etc<br>
 * Interacts with Stram with a heartbeat protocol<br>
 * <br>
 *
 * @author Chetan Narsude <chetan@malhar-inc.com>
 */
public class GenericNode extends Node<Operator>
{
  private static final Logger logger = LoggerFactory.getLogger(GenericNode.class);
  protected final HashMap<String, Reservoir> inputs = new HashMap<String, Reservoir>();
  protected int deletionId;

  protected abstract class Reservoir extends CircularBuffer<Object> implements Sink<Object>
  {
    protected int count;

    Reservoir()
    {
      super(bufferCapacity);
    }

    @Override
    public final void process(Object payload)
    {
      try {
        put(payload);
      }
      catch (InterruptedException ex) {
        logger.warn("Abandoning processing of the payload {} due to an interrupt", payload);
        throw new RuntimeException(ex);
      }
    }

    public abstract Tuple sweep();

  }

  private class InputReservoir extends Reservoir
  {
    final Sink<Object> sink;

    InputReservoir(Sink<Object> sink)
    {
      super();
      this.sink = sink;
    }

    @Override
    public final Tuple sweep()
    {
      final int size = size();
      for (int i = 1; i <= size; i++) {
        if (peekUnsafe() instanceof Tuple) {
          count += i;
          return (Tuple)peekUnsafe();
        }

        sink.process(pollUnsafe());
      }

      count += size;
      return null;
    }

  }

  public GenericNode(String id, Operator operator)
  {
    super(id, operator);
  }

  public void handleIdleTimeout()
  {
  }

  @Override
  public Sink<Object> connectInputPort(String port, final Sink<? extends Object> sink)
  {
    Sink<Object> retvalue;

    @SuppressWarnings("unchecked")
    InputPort<Object> inputPort = (InputPort<Object>)descriptor.inputPorts.get(port);
    if (inputPort == null) {
      retvalue = null;
    }
    else {
      if (sink == null) {
        Reservoir reservoir = inputs.remove(port);
        /**
         * since there are tuples which are not yet processed downstream, rather than just removing
         * the sink, it makes sense to wait for all the data to be processed on this sink and then
         * remove it.
         */
        if (reservoir != null) {
          inputs.put(port.concat(".").concat(String.valueOf(deletionId++)), reservoir);
          reservoir.process(new EndStreamTuple());
        }

        retvalue = null;
      }
      else {
        inputPort.setConnected(true);
        Reservoir reservoir = inputs.get(port);
        if (reservoir == null) {
          reservoir = new InputReservoir(inputPort.getSink());
          inputs.put(port, reservoir);
        }
        retvalue = reservoir;
      }
    }

    return retvalue;
  }

  /**
   * Originally this method was defined in an attempt to implement the interface Runnable.
   *
   * Although it seems that it's called from another thread which implements Runnable, so we take this
   * opportunity to pass the OperatorContextImpl through the run method. Note that activate does not return as
   * long as there is useful workload for the node.
   */
  @Override
  @SuppressWarnings({"SleepWhileInLoop"})
  public final void run()
  {
    final boolean handleIdleTime = operator instanceof IdleTimeHandler;
    boolean insideWindow = false;
    int windowCount = 0;
    int totalQueues = inputs.size();

    ArrayList<Reservoir> activeQueues = new ArrayList<Reservoir>();
    activeQueues.addAll(inputs.values());

    int expectingBeginWindow = activeQueues.size();
    int receivedResetTuples = 0;
    int receivedEndWindow = 0;

    Object lastEndWindow = null;
    try {
      do {
        Iterator<Reservoir> buffers = activeQueues.iterator();
        activequeue:
        while (buffers.hasNext()) {
          Reservoir activePort = buffers.next();
          Tuple t = activePort.sweep();
          if (t != null) {
            switch (t.getType()) {
              case BEGIN_WINDOW:
                if (expectingBeginWindow == totalQueues) {
                  activePort.remove();
                  expectingBeginWindow--;
                  currentWindowId = t.getWindowId();
                  for (int s = sinks.length; s-- > 0;) {
                    sinks[s].process(t);
                  }
                  if (windowCount == 0) {
                    insideWindow = true;
                    operator.beginWindow(currentWindowId);
                  }
                  receivedEndWindow = 0;
                }
                else if (t.getWindowId() == currentWindowId) {
                  activePort.remove();
                  expectingBeginWindow--;
                }
                else {
                  buffers.remove();
                }
                break;

              case END_WINDOW:
                if (t.getWindowId() == currentWindowId) {
                  lastEndWindow = activePort.remove();
                  if (++receivedEndWindow == totalQueues) {
                    if (++windowCount == applicationWindowCount) {
                      insideWindow = false;
                      operator.endWindow();
                      windowCount = 0;
                    }

                    for (final Sink<Object> output: outputs.values()) {
                      output.process(t);
                    }

                    buffers.remove();
                    assert (activeQueues.isEmpty());
                    activeQueues.addAll(inputs.values());
                    expectingBeginWindow = activeQueues.size();

                    handleRequests(currentWindowId);
                    break activequeue;
                  }
                  else {
                    buffers.remove();
                  }
                }
                else {
                  buffers.remove();
                }
                break;

              case RESET_WINDOW:
                /**
                 * we will receive tuples which are equal to the number of input streams.
                 */
                activePort.remove();

                if (receivedResetTuples++ == 0) {
                  for (int s = sinks.length; s-- > 0;) {
                    sinks[s].process(t);
                  }
                }
                else if (receivedResetTuples == activeQueues.size()) {
                  receivedResetTuples = 0;
                }
                break;

              case END_STREAM:
                activePort.remove();
                /**
                 * We are not going to receive begin window on this ever!
                 */
                expectingBeginWindow--;
                /**
                 * Since one of the operators we care about it gone, we should relook at our operators.
                 * We need to make sure that the END_STREAM comes outside of the window.
                 */
                totalQueues--;

                for (Iterator<Entry<String, Reservoir>> it = inputs.entrySet().iterator(); it.hasNext();) {
                  Entry<String, Reservoir> e = it.next();
                  if (e.getValue() == activePort) {
                    if (!descriptor.inputPorts.isEmpty()) {
                      descriptor.inputPorts.get(e.getKey()).setConnected(false);
                    }
                    it.remove();
                    break;
                  }
                }

                buffers.remove();
                if (totalQueues == 0) {
                  alive = false;
                  break activequeue;
                }
                else if (activeQueues.isEmpty()) {
                  assert (!inputs.isEmpty());
                  /*
                   * Do the same sequence as the end window since the current window is not ended.
                   */
                  operator.endWindow();
                  insideWindow = false;

                  assert (lastEndWindow != null);
                  for (final Sink<Object> output: outputs.values()) {
                    output.process(lastEndWindow);
                  }

                  activeQueues.addAll(inputs.values());
                  expectingBeginWindow = activeQueues.size();

                  handleRequests(currentWindowId);
                  break activequeue;
                }
                break;

              case CHECKPOINT:
                activePort.remove();
                break;

              default:
                throw new UnhandledException("Unrecognized Control Tuple", new IllegalArgumentException(t.toString()));
            }
          }
        }

        if (activeQueues.isEmpty() && alive) {
          logger.error("Invalid State - the node blocked forever!!!");
        }
        else {
          boolean need2sleep = true;
          for (Reservoir cb: activeQueues) {
            if (cb.size() > 0) {
              need2sleep = false;
              break;
            }
          }

          if (need2sleep) {
            Thread.sleep(spinMillis);
            if (handleIdleTime) {
              for (Reservoir cb: activeQueues) {
                if (cb.size() > 0) {
                  need2sleep = false;
                  break;
                }
              }

              /*
               * there is still no work scheduled for the operator, so lets give a chance to the operator to handle timeout.
               */
              if (need2sleep) {
                ((IdleTimeHandler)operator).handleIdleTime();
              }
            }
          }
        }
      }
      while (alive);
    }
    catch (InterruptedException ex) {
      alive = false;
    }
    catch (RuntimeException ex) {
      if (ex.getCause() instanceof InterruptedException) {
        alive = false;
      }
      else {
        throw ex;
      }
    }

    if (insideWindow) {
      operator.endWindow();
      emitEndWindow();
    }
  }

  @Override
  protected void reportStats(OperatorStats stats)
  {
    super.reportStats(stats);
    ArrayList<PortStats> ipstats = new ArrayList<PortStats>();
    for (Entry<String, Reservoir> e: inputs.entrySet()) {
      ipstats.add(new PortStats(e.getKey(), e.getValue().count));
      e.getValue().count = 0;
    }

    stats.inputPorts = ipstats;
  }

}
