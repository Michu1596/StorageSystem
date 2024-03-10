package cp2023.solution;

import cp2023.base.ComponentId;
import cp2023.base.DeviceId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

public class Device {
    private final ConcurrentHashMap<ComponentId, Semaphore> loweredSemaphores;
    // occupied slots during freeing and not only
    private final LinkedBlockingQueue<Semaphore> raisedSemaphores;
    // free slots; this queue will be empty if the queue returnedLoweredSemaphores is empty
    private final LinkedBlockingQueue<Semaphore> returnedLoweredSemaphores; // slots being freed

    private final Semaphore mutexDev;
    public Device(int allSlots, Map<ComponentId, DeviceId> compPlacement, DeviceId devId){
        mutexDev = new Semaphore(1);
        loweredSemaphores = new ConcurrentHashMap<>();
        raisedSemaphores = new LinkedBlockingQueue<>();
        returnedLoweredSemaphores = new LinkedBlockingQueue<>();
        for(Map.Entry<ComponentId, DeviceId> pair : compPlacement.entrySet()){
            if(devId.equals(pair.getValue()))
                loweredSemaphores.put(pair.getKey(), new Semaphore(0));
        }
        for(int i = 0; i <allSlots - loweredSemaphores.size(); i++)
            raisedSemaphores.add(new Semaphore(1));
    }

    /**
     * method to be called by the procedure adding a component to the device (i.e. Addition or Relocation) when
     * permission is obtained. Returns a semaphore to wait for a slot to perform the perform method. It is not known
     * whether the semaphore will be raised or lowered
     *
     * @param component to be added
     * @return semaphore to wait for a slot
     */
    public Semaphore waitForSlot(ComponentId component){
        Semaphore semaphore;
        try {
            mutexDev.acquire();
            if(!raisedSemaphores.isEmpty()) {
                // raisedSemaphores cant get a new element if returnedLoweredSemaphores is empty
                semaphore = raisedSemaphores.remove();
                loweredSemaphores.put(component, semaphore);
                mutexDev.release();
                return semaphore;
            }
            for(Semaphore semafor2 : returnedLoweredSemaphores){
                // if there is a raised semaphore in the queue, we return it
                if(semafor2.availablePermits() > 0) {
                    returnedLoweredSemaphores.remove(semafor2);
                    loweredSemaphores.put(component, semafor2);
                    mutexDev.release();
                    return semafor2;
                }
            }

            mutexDev.release();

            semaphore = returnedLoweredSemaphores.take();
            // wait till a slot is freed
        }
        catch (InterruptedException e){
            throw new RuntimeException("panic: unexpected thread interruption");
        }

        loweredSemaphores.put(component, semaphore);
        // till the slot is freed, the semaphore will be in two different places in the map
        return semaphore;
    }


    /**
     * this procedure is called by the function freeing a slot on the device (i.e. Removal or Relocation) when
     * permission is obtained
     * @param component component to be removed
     */
    public void slotBeingFreed(ComponentId component){
        returnedLoweredSemaphores.add(loweredSemaphores.get(component));
        System.out.println("    COMPONENT " + component + " IS BEING FREED");
    }

    /**
     * this procedure is called by the function freeing a slot on the device (i.e. Removal or Relocation) after
     * calling the prepare function
     * @param component component to be removed
     * */
    public void slotFreed(ComponentId component){
        try {
            mutexDev.acquire();
            Semaphore freedSlot = loweredSemaphores.get(component);
            freedSlot.release();
            loweredSemaphores.remove(component);
            mutexDev.release();
        }
        catch (InterruptedException e){
            throw new RuntimeException("panic: unexpected thread interruption");
        }

    }

    /**
     * true if there is a free slot or a slot is being freed
     * @return true if there is a free slot
     */
    public boolean isFree(){
        if(returnedLoweredSemaphores.size() > 0 || raisedSemaphores.size() > 0)
            return true;
        return false;
    }

    /**
     * called when a cycle is found together with addLoweredSem
     * @param component componentId
     * @return semaphore to wait for a slot
     */
    public Semaphore getLoweredSem(ComponentId component){
        return loweredSemaphores.remove(component);
    }

    /**
     * called when a cycle is found together with getLoweredSem
     * @param component komponent
     */
    public void addLoweredSem(Semaphore semaphore, ComponentId component){
        loweredSemaphores.put(component, semaphore);
    }
}
