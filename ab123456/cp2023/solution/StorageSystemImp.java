package cp2023.solution;

import cp2023.base.ComponentId;
import cp2023.base.ComponentTransfer;
import cp2023.base.DeviceId;
import cp2023.base.StorageSystem;
import cp2023.exceptions.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;

public class StorageSystemImp implements StorageSystem {
    //celowo sa widoczne w calym pakiecie
    final ConcurrentHashMap<DeviceId, Integer> deviceTotalSlotsCon;
    final ConcurrentHashMap<DeviceId, Urzadzenie> urzadzenia;
    final ConcurrentHashMap<ComponentId, DeviceId> componentPlacementCon;
    final ConcurrentHashMap<ComponentId, Boolean> wTrakcieOperacji;
    final Semaphore mutexPoprawnoscPozwolenie;

    final ConcurrentHashMap<DeviceId, Integer> ileDodawanCZeka; // potrzebny do
    // dziedziczenia sekcji krytycznej. Modyfikowane tylko w klasie Dodanie
    // rodzaje transferow

    final ConcurrentHashMap<DeviceId, ConcurrentLinkedQueue<Przeniesienie>> transferyDo;
    // transfery konczace cykl; DeviceId = sourceDev

    public StorageSystemImp(Map<DeviceId, Integer> deviceTotalSlots, Map<ComponentId, DeviceId> componentPlacement){
        deviceTotalSlotsCon = new ConcurrentHashMap<>();
        componentPlacementCon = new ConcurrentHashMap<>();
        wTrakcieOperacji = new ConcurrentHashMap<>();
        ileDodawanCZeka = new ConcurrentHashMap<>();
        transferyDo = new ConcurrentHashMap<>();
        ConcurrentHashMap<DeviceId, Integer> zapelnienieZarezerwowane = new ConcurrentHashMap<>();
        // mapa pomocnicza do sprawdzania czy nie przekroczono deklarowanego zapelnienia urzadzen
        urzadzenia = new ConcurrentHashMap<>();
        mutexPoprawnoscPozwolenie = new Semaphore(1);
        for(DeviceId dev : deviceTotalSlots.keySet()) {  // iteruje po urzadzeniach
            zapelnienieZarezerwowane.put(dev, 0); // ustawiamy zapelnienie na 0
            urzadzenia.put(dev, new Urzadzenie(deviceTotalSlots.get(dev), componentPlacement, dev));
        }

        deviceTotalSlotsCon.putAll(deviceTotalSlots);
        componentPlacementCon.putAll(componentPlacement);
        for(ComponentId komponent : componentPlacement.keySet()) { // iteruje po komponentach
            wTrakcieOperacji.put(komponent, false);
            // jesli w componentPlacement wystepuje urzadzenie ktorego nie ma na liscie urzadzen to cos jest nie tak
            if(!deviceTotalSlots.containsKey(componentPlacement.get(komponent)))
                throw new IllegalArgumentException("W componentPlacement wystepuje komponenet ktorego nie ma " +
                        "w deviceTotalSlots");
            zapelnienieZarezerwowane.put(componentPlacement.get(komponent),
                    zapelnienieZarezerwowane.get(componentPlacement.get(komponent)) + 1); // ustawienie zapelnienia
                // ^ zwiekszenie zapelnienia o 1 przy napotkaniu komponentu znajdujacego sie na odpowiednim urzadzeniu
        }
        for(Map.Entry<DeviceId, Integer> para : zapelnienieZarezerwowane.entrySet()){
            if (para.getValue() > deviceTotalSlots.get(para.getKey()))
                throw new IllegalArgumentException("Liczba" + para.getValue() + " komponentow na urzadzeniu: " +
                        para.getKey() + " przekracza deklarowana pojemnosc tego urzadzenia: " +
                        deviceTotalSlots.get(para.getKey()) );
        }

    }

    /**okresla typ transferu i stwierdza jego poprawnosc. W przypadu transferu niepoprawnego rzuca odpowiedni
    * wyjatek. wartosc zwracana przyjmuje wartosci  1 - 3
    * 1 - dodanie komponentu
    * 2 - przeniesnie komponentu
    * 3 - usuniecie komponentu*/
    private int typTransferu(ComponentTransfer transfer) throws TransferException{
        // okreslenie typu i poprawnosci transferu
        int typTransferu = 0; //  1 - dodanie komp 2 - przeniesienie komp 3 - usuniecie komp 0 - transfer niepoprawny
        if(transfer.getSourceDeviceId() == null && transfer.getDestinationDeviceId() != null){ // 1 - dodanie komp
            Dodanie.czyPoprawneDodanie(transfer, this);
            typTransferu = 1;
        }
        if(transfer.getSourceDeviceId() != null && transfer.getDestinationDeviceId() != null){ // 2 - przeniesienie komp
            Przeniesienie.czyPoprawnePrzeniesienie(transfer, this);
            typTransferu = 2;
        }
        if(transfer.getSourceDeviceId() != null && transfer.getDestinationDeviceId() == null) { // 3 - usuniecie
            Usuniecie.czyPoprawneUsuniecie(transfer, this);
            typTransferu = 3;
        }

        if(typTransferu == 0)
            throw new IllegalTransferType(transfer.getComponentId());


        return typTransferu;
    }

    @Override
    public void execute(ComponentTransfer transfer) throws TransferException {
        int typTransferu;
        try {
            mutexPoprawnoscPozwolenie.acquire();
            assert (mutexPoprawnoscPozwolenie.availablePermits() == 0);
            typTransferu = typTransferu(transfer); // moze wyrzucic wyjatek stad V(mutex) w finally
            TransefrAbstract transfer1;
            if(typTransferu == 1) {// dodanie komponentu
                transfer1 = new Dodanie(this, transfer);
                transfer1.sprobujWykonacTransfer();
            }
            else if (typTransferu == 2){  // przeniesieni
                transfer1 = new Przeniesienie(this, transfer);
                transfer1.sprobujWykonacTransfer();
            }
            else if (typTransferu == 3) { // usuniecie
                transfer1 = new Usuniecie(this, transfer);
                transfer1.sprobujWykonacTransfer();
            }

        }
        catch (InterruptedException e){
            throw new RuntimeException("panic: unexpected thread interruption");
        }
        catch (TransferException e){ // w razie niepoprawnosci transferu zwalniamy muteksa i rzucamy wyjatek dalej
            mutexPoprawnoscPozwolenie.release();
            throw e;
        }
    }
}
