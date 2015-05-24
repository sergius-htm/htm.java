package org.numenta.nupic.network;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.numenta.nupic.network.sensor.Sensor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Subscriber;

 

/**
 * Regions are collections of {@link Layer}s, which are in turn collections
 * of algorithmic components. Regions can be connected to each other to establish
 * a hierarchy of processing. To connect one Region to another, typically one 
 * would do the following:
 * </p><p>
 * <pre>
 *      Parameters p = Parameters.getDefaultParameters(); // May be altered as needed
 *      Network n = Network.create("Test Network", p);
 *      Region region1 = n.createRegion("r1"); // would typically add Layers to the Region after this
 *      Region region2 = n.createRegion("r2"); 
 *      region1.connect(region2);
 * </pre>
 * <b>--OR--</b>
 * <pre>
 *      n.connect(region1, region2);
 * </pre>
 * <b>--OR--</b>
 * <pre>
 *      Network.lookup("r1").connect(Network.lookup("r2"));
 * </pre>    
 * 
 * @author cogmission
 *
 */
public class Region {
    private static final Logger LOGGER = LoggerFactory.getLogger(Region.class);
    
    private Network network;
    private Map<String, Layer<Inference>> layers = new HashMap<>();
    private Observable<Inference> regionObservable;
    private Layer<?> tail;
    private Layer<?> head;
    
    /** Marker flag to indicate that assembly is finished and Region initialized */
    private boolean assemblyClosed;
    
    /** Temporary variables used to determine endpoints of observable chain */
    private HashSet<Layer<Inference>> sources;
    private HashSet<Layer<Inference>> sinks;
    
    private Object input;
    
    
    private String name;
    
    public Region(String name, Network container) {
        this.name = name;
        this.network = container;
    }
    
    /**
     * Closes the Region and completes the finalization of its assembly.
     * After this call, any attempt to mutate the structure of a Region
     * will result in an {@link IllegalStateException} being thrown.
     * 
     * @return
     */
    public Region close() {
        completeAssembly();
        
        Layer<?> l = tail;
        do {
            l.close();
        }while((l = l.getNext()) != null);
        
        return this;
    }
    
    /**
     * Returns a flag indicating whether this {@code Region} has had
     * its {@link #close} method called, or not.
     * 
     * @return
     */
    public boolean assemblyClosed() {
        return assemblyClosed;
    }
    
    /**
     * Used to manually input data into a {@link Region}, the other way 
     * being the call to {@link Region#start()} for a Region that contains
     * a {@link Layer} which in turn contains a {@link Sensor} <em>-OR-</em>
     * subscribing a receiving Region to this Region's output Observable.
     * 
     * @param input One of (int[], String[], {@link ManualInput}, or Map<String, Object>)
     */
    @SuppressWarnings("unchecked")
    public <T> void compute(T input) {
        this.input = input;
        ((Layer<T>)tail).compute(input);
    }
    
    /**
     * Returns the current input into the region. This value changes
     * after every call to {@link Region#compute(Object)}.
     * 
     * @return
     */
    public Object getInput() {
        return input;
    }
    
    /**
     * Adds the specified {@link Layer} to this {@code Region}. 
     * @param l
     * @return
     * @throws IllegalStateException if Region is already closed
     * @throws IllegalArgumentException if a Layer with the same name already exists.
     */
    @SuppressWarnings("unchecked")
    public Region add(Layer<?> l) {
        if(assemblyClosed) {
            throw new IllegalStateException("Cannot add Layers when Region has already been closed.");
        }
        
        if(sources == null) {
            sources = new HashSet<Layer<Inference>>();
            sinks = new HashSet<Layer<Inference>>();
        }
        
        // Set the sensor reference for global access.
        if(l.hasSensor()) {
            network.setSensor(l.getSensor());
            network.setEncoder(l.getSensor().getEncoder());
        }
        
        String layerName = name.concat(":").concat(l.getName());
        if(layers.containsKey(layerName)) {
            throw new IllegalArgumentException("A Layer with the name: " + l.getName() + " has already been added.");
        }
        
        l.name(layerName);
        layers.put(l.getName(), (Layer<Inference>)l);
        l.setRegion(this);
        
        return this;
    }
    
    /**
     * Returns the String identifier for this {@code Region}
     * @return
     */
    public String getName() {
        return name;
    }
    
    /**
     * Returns an {@link Observable} which can be used to receive
     * {@link Inference} emissions from this {@code Region}
     * @return
     */
    public Observable<Inference> observe() {
        return regionObservable;
    }
    
    /**
     * Calls {@link Layer#start()} on this Region's input {@link Layer} if 
     * that layer contains a {@link Sensor}. If not, this method has no 
     * effect.
     */
    public void start() {
        if(tail.hasSensor()) {
            LOGGER.info("Starting Region [" + getName() + "] input Layer thread.");
            tail.start();
        }else{
            LOGGER.warn("Start called on Region [" + getName() + "] with no effect due to no Sensor present.");
        }
    }
    
    /**
     * Stops each {@link Layer} contained within this {@code Region}
     */
    public void stop() {
        LOGGER.debug("Stop called on Region [" + getName() + "]");
        if(tail != null) {
            tail.halt();
        }
        LOGGER.debug("Region [" + getName() + "] stopped.");
    }
    
    /**
     * Connects the output of the specified {@code Region} to the 
     * input of this Region
     * 
     * @param inputRegion   the Region who's emissions will be observed by 
     *                      this Region.
     * @return
     */
    public Region connect(Region inputRegion) {
        return this;
    }
    
    /**
     * Connects two layers to each other in a unidirectional fashion 
     * with "toLayerName" representing the receiver or "sink" and "fromLayerName"
     * representing the sender or "source".
     * 
     * @param toLayerName       the name of the sink layer
     * @param fromLayerName     the name of the source layer
     * @return
     * @throws IllegalStateException if Region is already closed
     */
    public Region connect(String toLayerName, String fromLayerName) {
        if(assemblyClosed) {
            throw new IllegalStateException("Cannot connect Layers when Region has already been closed.");
        }
        
        Layer<Inference> in = lookup(toLayerName);
        Layer<Inference> out = lookup(fromLayerName);
        if(in == null) {
            throw new IllegalArgumentException("Could not lookup (to) Layer with name: " + toLayerName);
        }else if(out == null){
            throw new IllegalArgumentException("Could not lookup (from) Layer with name: " + fromLayerName);
        }
        
        // Set source's pointer to its next Layer --> (sink : going upward).
        out.next(in);
        // Set the sink's pointer to its previous Layer --> (source : going downward)
        in.previous(out);
        // Connect out to in
        connect(in, out);
        
        return this;
    }
    
    /**
     * Does a straight associative lookup by first creating a composite
     * key containing this {@code Region}'s name concatenated with the specified
     * {@link Layer}'s name, and returning the result.
     * 
     * @param layerName
     * @return
     */
    public Layer<Inference> lookup(String layerName) {
        return layers.get(name.concat(":").concat(layerName));
    }
    
    /**
     * Called by {@link #start()}, {@link #observe()} and {@link #connect(Region)}
     * to finalize the internal chain of {@link Layer}s contained by this {@code Region}.
     * This method assigns the head and tail Layers and composes the {@link Observable}
     * which offers this Region's emissions to any upstream {@link Region}s.
     */
    private void completeAssembly() {
        if(!assemblyClosed) {
            if(tail == null) {
                if(layers.size() > 1) {
                    Set<Layer<Inference>> temp = new HashSet<Layer<Inference>>(sources);
                    temp.removeAll(sinks);
                    if(temp.size() != 1) {
                        throw new IllegalArgumentException("Detected misconfigured Region too many or too few sinks.");
                    }
                    tail = temp.iterator().next();
                }else{
                    tail = layers.values().iterator().next();
                }
            }
            if(head == null) {
                if(layers.size() > 1) {
                    Set<Layer<Inference>> temp = new HashSet<Layer<Inference>>(sinks);
                    temp.removeAll(sources);
                    if(temp.size() != 1) {
                        throw new IllegalArgumentException("Detected misconfigured Region too many or too few sources.");
                    }
                    head = temp.iterator().next();
                }else{
                    head = layers.values().iterator().next();
                }
                
                regionObservable = head.observe();
            }
            assemblyClosed = true;
        }
    }
    
    /**
     * Called internally to "connect" two {@link Layer} {@link Observable}s
     * taking care of other connection details such as passing the inference
     * up the chain and any possible encoder.
     * 
     * @param in         the sink end of the connection between two layers
     * @param out        the source end of the connection between two layers
     * @throws IllegalStateException if Region is already closed
     */
    <I extends Layer<Inference>, O extends Layer<Inference>> void connect(I in, O out) {
        if(assemblyClosed) {
            throw new IllegalStateException("Cannot add Layers when Region has already been closed.");
        }
        
        //Pass the Inference object from lowest to highest layer.
        // The passing of the Inference is done in an Observable so that
        // it happens on every pass through the chain, while the Encoder
        // is only passed once, so the method is called directly here. We
        // don't check to see if it exists or is null, since it is already
        // null in the receiver, it doesn't hurt anything.
        Observable<Inference> o = out.observe().map(i -> {
            in.passInference(i);
            return i;
        });
        if(out.getEncoder() != null) {
            in.passEncoder(out.getEncoder());
        }
        
        sources.add(out);
        sinks.add(in);
        
        o.subscribe(new Subscriber<Inference>() {
            @Override public void onCompleted() {}
            @Override public void onError(Throwable e) { e.printStackTrace(); }
            @Override public void onNext(Inference i) {
                in.compute(i);
            }
        });
    }
    
}
