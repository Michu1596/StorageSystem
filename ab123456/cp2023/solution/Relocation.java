package cp2023.solution;

import cp2023.base.ComponentTransfer;
import cp2023.base.DeviceId;
import cp2023.exceptions.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.*;

public class Relocation extends TransefrAbstract{

    private final CountDownLatch zasowka;
    private CyclicBarrier bariera;
    private boolean czyZwalniacMuteksa;
    private boolean czyWCyklu;
    private boolean czyWSciezce;
    private boolean czyOstatniWSciezce;
    private Semaphore miejsceRzeczywiste;
    private Semaphore miejsceDoZwolnieniaNaKoniecSciezki; // pierwszy element sciezki przekazuje ostatniemu miejsce
    // rzeczywiste na ktore czeka, ostatni element sciezki wrzuca je do destDevice i na nim czeka

    public static boolean isRelocationCorrect(ComponentTransfer transfer,
                                              StorageSystemImp system) throws TransferException{
        if(!system.componentPlacementCon.containsKey(transfer.getComponentId()))
            throw new ComponentDoesNotExist(transfer.getComponentId(), transfer.getSourceDeviceId());
        if(!system.deviceTotalSlotsCon.containsKey(transfer.getDestinationDeviceId())) // czy dev docelowy istnieje
            throw new DeviceDoesNotExist(transfer.getDestinationDeviceId());
        if(!system.deviceTotalSlotsCon.containsKey(transfer.getSourceDeviceId())) // czy dev zrodlowy istnieje
            throw new DeviceDoesNotExist(transfer.getSourceDeviceId());
        if(system.duringOperation.get(transfer.getComponentId()))
            throw new ComponentIsBeingOperatedOn(transfer.getComponentId());
        if(!system.componentPlacementCon.get(transfer.getComponentId()).equals(
                transfer.getSourceDeviceId())) // komponent nie znajduje sie na urzadzeniu zrodlowym
            throw new ComponentDoesNotExist(transfer.getComponentId(), transfer.getSourceDeviceId());
        if(system.componentPlacementCon.get(transfer.getComponentId()).equals(
                transfer.getDestinationDeviceId())) // komponent znajduje sie juz na urz docelowym
            throw new ComponentDoesNotNeedTransfer(transfer.getComponentId(), transfer.getSourceDeviceId());

        return true;
    }
    public Relocation(StorageSystemImp system, ComponentTransfer transfer){
        super(system, transfer);
        zasowka = new CountDownLatch(1);
        czyZwalniacMuteksa = false;
    }
    private boolean czyIstniejeCykl(DeviceId zrodlo, int dlugoscCyklu){
        if(system.transfersTo.containsKey(zrodlo)){
            ConcurrentLinkedQueue<Relocation> lista = system.transfersTo.get(zrodlo);
            for(Iterator<Relocation> itr = lista.iterator(); itr.hasNext(); ){
                Relocation relocation = itr.next();
                // w tym miejscu caly czas porownujemy ze zrodlem tego samego przeniesienia
                if(relocation.srcId.equals(destId) ) {
                    bariera = new CyclicBarrier(dlugoscCyklu + 1); // gdy stwierdzilismy istnienie cyklu tworzymy
                    // bariere cykliczna ktora oczekuje na wszystkie watki z cyklu
                    relocation.bariera = bariera;// bariere trzeba przypisac przed obudzeniem
                    relocation.czyWCyklu = true;

                    relocation.zasowka.countDown(); // budzimy transfery pojedynczo
                    itr.remove();
                    if(lista.isEmpty())
                        system.transfersTo.remove(zrodlo);
                    return true;

                } else if (czyIstniejeCykl(relocation.srcId, dlugoscCyklu + 1)) {
                    // jesli istnieje cykl to mozna obudzic te przeniesienia ktore don naleza
                    relocation.bariera = bariera; // ustawiamy ta bariere wszystkim watkom
                    relocation.czyWCyklu = true;

                    relocation.zasowka.countDown(); // budzimy transfery pojedynczo
                    itr.remove();
                    if(lista.isEmpty())
                        system.transfersTo.remove(zrodlo);
                    return true;
                }
            }
        }
        return false;
    }

    // wykonuje transfer za pozwolneniem
    private void wykonaj(boolean pozwolenie){
        try {

            // fragment kodu odpowiedzialny za odopowiednie czekanie
            if(!pozwolenie) {
                zasowka.await(); // po obudzeniu z zasowki jestesmy w muteksie, ale nie zwalniamy go od razu bo na raz
                // budzi sie wiele watkow; zwalnia ten co obudzil
                system.duringOperation.put(compId, true);
                system.componentPlacementCon.put(compId, destId); // srcId w mapie podmieniamy na destId
                if(czyWSciezce && czyOstatniWSciezce) { // ostani ze sciezki zwalnia miejsce
                    System.out.println("    PRZENIESNIE OSTATNIE W SCIEZECE " + this + " JEST W TRAKC ZWLANIANIA MSC NA " + srcId);
                    system.devices.get(srcId).miejsceWTrakcieZwalniania(compId);
                }
                bariera.await();
                if(czyZwalniacMuteksa){ // jesli nie jest w cyklu i zostal obudzony w sekcji krytycznej
                    // to musi podniesc muteksa
                    system.mutex.release();
                }
            }

            if(czyWCyklu) {
                Semaphore semaforNaKomponentPrzenoszony = system.devices.get(srcId).dajIUsunOpuszczonySemafor(compId);
                transfer.prepare();
                bariera.await(); // gdy wszystkie transfery wykonaja prepare mozna przejsc dalej
                system.devices.get(destId).dodajOpuszczonySemafor(semaforNaKomponentPrzenoszony, compId);
            } else if (czyWSciezce) {
                if(czyOstatniWSciezce) {
                    miejsceDoZwolnieniaNaKoniecSciezki.acquire();
                    bariera.await();
                    transfer.prepare();
                    system.devices.get(destId).dodajOpuszczonySemafor(miejsceDoZwolnieniaNaKoniecSciezki, compId);
                    // przedostatni element w sciezce dostaje semafor ktory na poczatku otrzymal pierwszy element ze
                    // sciezki
                    bariera.await();
                    system.devices.get(srcId).zwolnionoMiejsce(compId);
                }
                else {
                    bariera.await(); // reszta przypadkow czeka
                    Semaphore semaforNaKomponentPrzenoszony = system.devices.get(srcId).dajIUsunOpuszczonySemafor(compId);
                    transfer.prepare();
                    bariera.await(); // gdy wszystkie transfery wykonaja prepare mozna przejsc dalej
                    system.devices.get(destId).dodajOpuszczonySemafor(semaforNaKomponentPrzenoszony, compId);
                }
            }
            else {
                transfer.prepare();
                system.devices.get(srcId).zwolnionoMiejsce(compId); // po wykonaniu prepare
                // "zwalniamy" miejsce na srcDev
                miejsceRzeczywiste.acquire(); // transfer przed wykonaniem perform musi sie upewnic
                // ze na urzadzeniu faktycznie znajduje sie miejsce
            }

            transfer.perform();

            system.duringOperation.put(compId, false); // to nie musi byc w muteksie; transfery na tym komponencie moga
            // zaczac sie wykonywac nawet gdy reszta transferow z cyklu jest w trakcie wykonania

            system.mutex.acquire();
            if (system.transfersTo.containsKey(destId)) {
                system.transfersTo.get(destId).remove(this); // czyscimy mape
                if(system.transfersTo.get(destId).isEmpty())
                    system.transfersTo.remove(destId);
            }
            system.mutex.release();
        }
        catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        } catch (BrokenBarrierException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }

    }

    /**
     * metoda do budzenia pojedynczego transferu nie bedacego czescia cyklu
     */
    public void obudz(){
        bariera = new CyclicBarrier(1); // nie musi czekac na inne watki bo jest sam
        czyZwalniacMuteksa = true; // odziedziczyl sekcje krytyczna
        czyWCyklu = false;
        miejsceRzeczywiste = system.devices.get(destId).czekajNaMiejsce(compId); // wiemy ze nie bedziemy czekac bo
        // obudzilo nas usuwanie komponenetu

        system.duringOperation.put(compId, true);
        system.componentPlacementCon.put(compId, destId); // srcId w mapie podmieniamy na destId

        if(system.additionsWaiting.containsKey(srcId)) { // w pierwszej kolejnosci sprawdzamy czy nie ma dodawania
            system.devices.get(srcId).miejsceWTrakcieZwalniania(compId);
            czyZwalniacMuteksa = false;
        }
        else if (system.transfersTo.containsKey(srcId)) { // sciezka
            DeviceId id = srcId;
            czyWSciezce = true;
            ArrayList<Relocation> sciezka = new ArrayList<>();
            Relocation temp;
            while (system.transfersTo.containsKey(id)) {
                temp = system.transfersTo.get(id).remove();
                sciezka.add(temp);

                if (system.transfersTo.get(id).isEmpty())
                    system.transfersTo.remove(id);

                id = temp.destId;
            }
            bariera = new CyclicBarrier(sciezka.size() + 1);
            sciezka.get(sciezka.size()-1).czyOstatniWSciezce = true; // ustawiam ostatni na sciezce
            sciezka.get(sciezka.size()-1).miejsceDoZwolnieniaNaKoniecSciezki = miejsceRzeczywiste;
            system.devices.get(destId).dajIUsunOpuszczonySemafor(compId); // od teraz tym miejscem zajmuje sie
            // ostatni element sciezki
            for(Relocation relocation : sciezka){
                relocation.czyWSciezce = true;
                relocation.bariera = bariera;
                relocation.czyWCyklu = false;
                relocation.zasowka.countDown();
            }
        }
        else {
            system.devices.get(srcId).miejsceWTrakcieZwalniania(compId);
        }

        zasowka.countDown();
    }

    @Override
    public boolean tryPerformTransfer() {
        system.duringOperation.put(compId, true); // do czasu wykonania transferu komponent nie moze byc poddany innym
        // transferom

        if (czyIstniejeCykl(srcId, 1) ) { // priorytet ma cykl
            czyWCyklu = true;
            system.componentPlacementCon.put(compId, destId); // srcId w mapie podmieniamy na destId
            try {
                bariera.await(); // czekamy az wszystkie watki zmodyfikuja metadane i zwalaniuamy muteksa
            } catch (BrokenBarrierException | InterruptedException e) {
                throw new RuntimeException("panic: unexpected thread interruption");
            }
            system.mutex.release();
            wykonaj(true);
            return true;
        } else if (system.devices.get(destId).czyWolne()) {
            czyWCyklu = false;
            miejsceRzeczywiste = system.devices.get(destId).czekajNaMiejsce(compId);
            system.componentPlacementCon.put(compId, destId); // srcId w mapie podmieniamy na destId
            if(system.additionsWaiting.containsKey(srcId)) { // jesli czeka dodawnaie to dziedziczy sekcje krytyczna
                system.devices.get(srcId).miejsceWTrakcieZwalniania(compId); // informujemy ze na urzadzeniu srcId
                // zaraz pojawilo wolne miejsce
            }
            else if(system.transfersTo.containsKey(srcId)){ // obudzony transfer tez dziedziczy
                DeviceId id = srcId;
                czyWSciezce = true;
                ArrayList<Relocation> sciezka = new ArrayList<>();
                Relocation temp;
                while (system.transfersTo.containsKey(id)) {
                    temp = system.transfersTo.get(id).remove();
                    sciezka.add(temp);

                    if (system.transfersTo.get(id).isEmpty())
                        system.transfersTo.remove(id);

                    id = temp.destId;
                }
                bariera = new CyclicBarrier(sciezka.size() + 1);
                sciezka.get(sciezka.size()-1).czyOstatniWSciezce = true; // ustawiam ostatni na sciezce
                sciezka.get(sciezka.size()-1).miejsceDoZwolnieniaNaKoniecSciezki = miejsceRzeczywiste;
                system.devices.get(destId).dajIUsunOpuszczonySemafor(compId); // od teraz tym miejscem zajmuje sie
                // ostatni element sciezki
                for(Relocation relocation : sciezka){
                    relocation.czyWSciezce = true;
                    relocation.bariera = bariera;
                    relocation.czyWCyklu = false;
                    relocation.zasowka.countDown();
                }
            }
            else{
                system.devices.get(srcId).miejsceWTrakcieZwalniania(compId); // informujemy ze na urzadzeniu srcId
                // zaraz pojawilo wolne miejsce
                system.mutex.release();
            }
            bariera = new CyclicBarrier(1); // nie musi sie zatrzymywac na barierze
            wykonaj(true);
            return true;

        } else { // cykl nie istnieje
            if (system.transfersTo.containsKey(destId))
                system.transfersTo.get(destId).add(this);
            else {
                ConcurrentLinkedQueue<Relocation> nowa = new ConcurrentLinkedQueue<>();
                nowa.add(this);
                system.transfersTo.put(destId, nowa);
            }
            system.mutex.release();
            wykonaj(false); // wieszamy go na zasowce
            return false;
        }
    }

    @Override
    public String toString(){
        return "Z " + srcId + " DO " + destId + " " + compId;
    }
}
