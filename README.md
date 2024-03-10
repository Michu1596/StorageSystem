# Concurrent storage system 

At the same time, storage systems must meet many requirements, in particular, the performance of data access operations, the use of data storage capacity, and the failure of devices that are those carriers. For this purpose, they often move data fragments between individual devices. 
This project implements in the Java mechanisms coordinating concurrent operations of such transfer by the following specyfication. 

# The specification
In our system model, data is grouped into components and stored on devices in such units. Each device and each data component are assigned an unchanged and unique identifier within the system (class object respectively `cp2023.base.DeviceIdand` the `cp2023.base.ComponentId`). ). Each device also has a certain capacity, that is, the maximum number of components that can be stored at any time. Assignment of components to devices is managed by the system (object implementing the interface `cp2023.base.StorageSystem`, shown below:
```
public interface StorageSystem {

    void execute(ComponentTransfer transfer) throws TransferException;
    
}
```

More specifically, each existing component in the system is on exactly one device, unless the system user has commissioned this component to transfer to another device (by calling the method `execute` the above class `StorageSystemand` passing it as a parameter an object implementing the interface `cp2023.base.ComponentTransferrepresenting` the commissioned transfer).
```
public interface ComponentTransfer {

    public ComponentId getComponentId();
    
    public DeviceId getSourceDeviceId();
    
    public DeviceId getDestinationDeviceId();
    
    public void prepare();
    
    public void perform();

}
```
The transfer of the component is also outsourced when the user wants to add a new component to the system (in this case the method `getSourceDeviceIdTransfer` object returns the value `null`) or remove an existing component from the system (in this case, symmetrically, method `getDestinationDeviceIdTransfer` object returns the value `null`). ). In other words, a single transfer represents one of the three available operations on the component:

- _adding_ a new component to the system device ( `getSourceDeviceId` transfer object returns `null` and `getDestinationDeviceId` non-null the device identifier to be on which the added component is to be located),
- _Relocation_ of an existing component between system devices ( `getSourceDeviceId` and the `getDestinationDeviceId` both return non-null representing the identifiers of the correspondingly current device on which the component is located and the target device on which the component should be located after the transfer),
- _removal_ of an existing component from the device and thus – the system ( `getSourceDeviceId` returns non-null identification of the device on which the component is located, and `getDestinationDeviceId` returns `null`). ).

Ordering three of the above types of operations by the user is out of the control of the implemented solution. The task of your solution is to carry out the ordered transfers in a synchronous manner (i.e. if the commissioned transfer is correct, the method `execute` is called on the implementation object `StorageSystem` with the transfer represented as the parameter implementing the interface `ComponentTransfer` cannot finish his action until the transfer is over). As many different operations can be commissioned simultaneously by the user, the system you implement must ensure that they are coordinated according to the following rules.

At any time, at most one transfer may be submitted for a given component. Until this transfer is complete, the component will be called the _transferred_, and each subsequent transfer reported for this component should be considered incorrect.

The transfer of the component itself is two-stage and can last for a longer period (especially its second stage). The transfer's start consists of its preparation (i.e., calling the method prepared by the object with the interface `ComponentTransfer` representing the transfer). Only after such preparation can data be transferred as a component (which is done by calling the method perform for the above-mentioned object). When the data is sent (i.e. method `perform` will finish its operation), the transfer is over. Both methods must be made in the context of the transfer ordering thread.

# Safety

The transfer can be correct or incorrect. The following security requirements apply to correct transfers. In turn, the handling of incorrect transfers is described in the further section.

If the transfer represents a component removal operation, it is _allowed_ to start without any additional preconditions. Otherwise, the start of the transfer is allowed if there is a place for the transferred component on the target device, in particular, the place is or will be released so that you can book it. More specifically, starting a transfer representing the transfer or adding a _Cx_ component is allowed if one of the following conditions occurs:

- On the transfer target device, there is a free space for a component that has not been booked by the system for another component that is/will be moved/admitted to that device.
- On the target device there is a _Cy_ component transferred from this device, the transfer of which has begun or its start is allowed, and the space released by this component has not been reserved by the system for another component.
- The _Cx_ component belongs to a certain set of transferred components such that the target device of each component from the harvest is a device on which exactly one other component is located, and the place of none of the components from the harvest has been reserved for a component outside the harvest.

If the transfer of the Cx component is allowed but takes place still occupied by another transferred Cy component (two last cases above), then the second stage of the transfer of the Cx component (i.e. call the function `perform` for this transfer) cannot begin before the end of the first stage of the transfer of the _Cy_ component (i.e. calling the function `prepare` for this transfer).

Of course, if the transfer of a component is not allowed, it cannot be started (i.e. function `prepare`, not the function `perform` .They cannot be called on the object representing this transfer).


# Liveness

When it comes to lifespan, the transfer (both its phase `prepare` and  `perform`) should start as soon as it is permitted and the other safety requirements will be met. If several transfers compete for space on the device, among those that are allowed.

# Handling of errors

Finally, the proposed solution should check whether the transfer ordered by the user is incorrect (which should increase by the method executeThe interface StorageSystemThe appropriate inheritance exception in the class `cp2023.exceptions.TransferException` ). According to previous explanations, the transfer is incorrect if at least one of the following conditions occurs:

- The transfer does not represent any of the three available operations on the components or does not indicate any component (except IllegalTransferType); and
- The device indicated by the transfer as source or target does not exist in the system (except DeviceDoesNotExist); and
- A component with an identifier equal to the transfer component already exists in the system (except ComponentAlreadyExists); and
- A component with an identifier equal to the deleted or transferred component does not exist in the system or is on a device other than indicated by the transfer (except ComponentDoesNotExist); and
- The transfer component is already on the device indicated by the transfer as target (except ComponentDoesNotNeedTransfer); and
- The transfer component is still being transferred (except ComponentIsBeingOperatedOn). ).

