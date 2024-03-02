package cp2023.solution;

import cp2023.base.ComponentTransfer;
import cp2023.exceptions.ComponentDoesNotExist;
import cp2023.exceptions.ComponentIsBeingOperatedOn;
import cp2023.exceptions.DeviceDoesNotExist;
import cp2023.exceptions.TransferException;


public class Usuniecie extends TransefrAbstract{
    public Usuniecie(StorageSystemImp system, ComponentTransfer transfer){
        super(system, transfer);
    }

    public static boolean czyPoprawneUsuniecie(ComponentTransfer transfer,
                                               StorageSystemImp system) throws TransferException{
        if(!system.componentPlacementCon.containsKey(transfer.getComponentId())) // komp nie istnieje
            throw new ComponentDoesNotExist(transfer.getComponentId(), transfer.getSourceDeviceId());
        if(!system.deviceTotalSlotsCon.containsKey(transfer.getSourceDeviceId())) // czy dev zrodlowy istnieje
            throw new DeviceDoesNotExist(transfer.getSourceDeviceId());
        if(!system.componentPlacementCon.get(transfer.getComponentId()).equals(
                transfer.getSourceDeviceId())) // komponent nie znajduje sie na urzadzeniu zrodlowym
            throw new ComponentDoesNotExist(transfer.getComponentId(), transfer.getSourceDeviceId());
        if(system.wTrakcieOperacji.get(transfer.getComponentId()))
            throw new ComponentIsBeingOperatedOn(transfer.getComponentId());
        return true;
    }
    @Override
    public boolean sprobujWykonacTransfer(){
        system.wTrakcieOperacji.put(compId, true);
        system.componentPlacementCon.remove(compId);
        if (system.ileDodawanCZeka.containsKey(srcId)){
            system.urzadzenia.get(srcId).miejsceWTrakcieZwalniania(compId); // budzimy dodawanie, ktore dzedziczy
            // sekcje krytyczna wiec nie zwalniamy muteksa
            usuwanieKomponentu();
            return true;
        } else if (system.transferyDo.containsKey(srcId)) {
            system.urzadzenia.get(srcId).miejsceWTrakcieZwalniania(compId); // wiemy ze nie obudzimy Dodania
            Przeniesienie doObudzenia = system.transferyDo.get(srcId).remove();
            doObudzenia.obudz(); // dziedziczy sek kryt wiec nie zwalniamy muteksa
            usuwanieKomponentu();
            return true;
        }
        else {
            system.urzadzenia.get(srcId).miejsceWTrakcieZwalniania(compId); // zwiekszamy wartosc na semaforze
            system.mutexPoprawnoscPozwolenie.release();
            usuwanieKomponentu(); // usuwamy
            return true;
        }
    }
    private void usuwanieKomponentu(){
        transfer.prepare();
        system.urzadzenia.get(srcId).zwolnionoMiejsce(compId);
        transfer.perform();
        system.wTrakcieOperacji.remove(compId);
    }
}
