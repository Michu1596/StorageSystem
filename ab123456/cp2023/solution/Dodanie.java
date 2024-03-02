package cp2023.solution;

import cp2023.base.ComponentTransfer;
import cp2023.exceptions.ComponentAlreadyExists;
import cp2023.exceptions.DeviceDoesNotExist;
import cp2023.exceptions.TransferException;
import java.util.concurrent.Semaphore;

public class Dodanie extends TransefrAbstract {

    private Semaphore miejsceRzeczywiste;

    /**
     * sprawdza poprawnosc transferu przy zaozeniu ze jest to transfer dodanie
     * @param transfer
     * @param system
     * @return true jesli transfer jest poprwany
     * @throws TransferException gdy transfer jest nieporawny
     */
    public static boolean czyPoprawneDodanie(ComponentTransfer transfer,
                                             StorageSystemImp system) throws TransferException {
        if(!system.deviceTotalSlotsCon.containsKey(transfer.getDestinationDeviceId())) // czy urzadz istneije
            throw new DeviceDoesNotExist(transfer.getDestinationDeviceId());
        if(system.componentPlacementCon.containsKey(transfer.getComponentId())) // komponent juz istnieje w systemie
            throw new ComponentAlreadyExists(transfer.getComponentId(),
                    system.componentPlacementCon.get(transfer.getComponentId()));
        return true;
    }

    public Dodanie(StorageSystemImp system, ComponentTransfer transfer){
        super(system, transfer);
    }

    private void dodawanieKomponentu(boolean pozwolenie){
        try {
            if(!pozwolenie) {
                miejsceRzeczywiste = system.urzadzenia.get(destId).czekajNaMiejsce(compId);
                // wiemy ze powiesimy sie na semaforze, ktory zostanie
                // podniesiony przez inny transfer. Jednak PRZED jego podniesieniem opuszczony zostanie muteks, po to by
                // mozna uzupelnic mapy
                system.ileDodawanCZeka.put(destId, system.ileDodawanCZeka.get(destId) - 1); // po obudzeniu zmniejszamy
                // liczbe oczekujacych
                if (system.ileDodawanCZeka.get(destId) == 0)
                    system.ileDodawanCZeka.remove(destId);
                system.componentPlacementCon.put(compId, destId); // po przyjeciu transferu do realizacji sygnalizuje ze
                // komponent bedzie znajdowal sie na wskazanym urzadzeniu i od tej chwily kazdy nastepny transfer, probujacy
                // umiescic ten komponent na tym urzadzeniu docelowym, traktowany bedzie jako niepoprawny
                system.mutexPoprawnoscPozwolenie.release(); // dziedziczy sekcje krytyczna po transferze ktory go
                // obudzil
            }
            transfer.prepare(); // Wykonujemy pierwszy etap, z drugim potencjalnie czekajac na zakonczenie 1. etapu dla
            // jakiegos komponentu na okupowanym urzadzeniu
            miejsceRzeczywiste.acquire();
            transfer.perform();
            system.wTrakcieOperacji.put(compId, false);
        }
        catch (InterruptedException e){
            throw new RuntimeException("panic: unexpected thread interruption");
        }

    }

    /**
     * w razie pozwolenia obsluguje mapy z rezerwacjami miesjaca, gdy go nie ma powinna zroibc to funkcja dodawanie
     * komponenu. Ta metoda dziala w muteksie!
     * @param
     * @return czy tranfer jest dozwolony
     */
    @Override
    public boolean sprobujWykonacTransfer(){
        system.wTrakcieOperacji.put(compId, true); // do czasu skonczenia operacji nie mozna poddawac innym transferom
        if(system.urzadzenia.get(destId).czyWolne()){
            system.componentPlacementCon.put(compId, destId);
            miejsceRzeczywiste = system.urzadzenia.get(destId).czekajNaMiejsce(compId);
            system.mutexPoprawnoscPozwolenie.release(); // podnosimy muteksa bo teraz juz zaden transfer nie zabierze
            // miejsca potrzebnego na komponent
            dodawanieKomponentu(true);
            return true;
        }
        if(system.ileDodawanCZeka.containsKey(destId))
            system.ileDodawanCZeka.put(destId, system.ileDodawanCZeka.get(destId) + 1);
        else
            system.ileDodawanCZeka.put(destId, 1);

        system.mutexPoprawnoscPozwolenie.release(); // podnosimy muteksa
        dodawanieKomponentu(false);
        return false;
    }
}
