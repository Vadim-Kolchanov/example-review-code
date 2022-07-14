import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

// TODO создать unit-тесты
/**
 * При вызове метода indexAllTxtInPath и передачи ему параметра pathToDir (путь к директории с файлами)
 * наполняется мапа invertedIndex, где ключ - это слово, а значение - лист классов Pointer,
 * который содержит в себе два поля: filePath - путь к файлу и count - кол-во слова в этом файле.
 */
// Название класса должно отражать его представление чего-то, в данном случае он является представителем файлов
// и может нам предоставить информацию о кол-ве вхождения слов
// Я бы его назвал FileWorker
public class Index {
    // 1) Обязательна инкапсуляция private и неизменяемость final
    // 2) Я бы предложил назвать мапу wordsFrequencyInFile или просто wordsFrequency
    // 3) А точно ли нам нужна TreeMap? Мы же работаем с многопоточкой, а она не синхронизирована.
    //    Можно использовать или в таком виде Collections.synchronizedNavigableMap(new TreeMap<String, List<Pointer>>()),
    //    или использовать вариант быстрей - ConcurrentHashMap и потом в get методе обернуть в new TreeMap<>(map)
    //    Тем самым мы решим будущие проблемы с синхронизацией, с сортировкой и с модификацией мапы из вне
    TreeMap<String, List<Pointer>> invertedIndex;

    // private final
    ExecutorService pool;

    public Index(ExecutorService pool) {
        this.pool = pool;

        // инициализировал бы в самом поле класса
        invertedIndex = new TreeMap<>();
    }

    // Предложил бы название: countFrequencyWordsInFilesByDirPath
    // потому что метод нам посчитает частоту слов в файлах, которые находятся в каталоге (формально IndexTask считает, но начинается все с этого метода)
    public void indexAllTxtInPath(String pathToDir) throws IOException {
        // Переименовать в dir
        Path of = Path.of(pathToDir);

        // Магическое число 2. Перенести в константу класса: private static final int MAX_SIZE_QUEUE = 2
        BlockingQueue<Path> files = new ArrayBlockingQueue<>(2);

        // try - это хорошо, а catch ещё лучше :) Чтобы описать ошибку подробней и уже передавать наверх
        try (Stream<Path> stream = Files.list(of)) {
            // 1) Метод add выбрасит исключение при полной очереди. Можно использовать put, но нужно обработать InterruptedException
            //    и понимать, что put при полной очереди блокирует поток.
            // 2) Подумать насчет разделения отвественности. В отдельном потоке получение файлов из директории (provider), в другом потоке выполнение IndexTask (consumer)
            // 3) А какой тип файла нам требуется? Только txt?
            stream.forEach(files::add);
        }

        // цикл For наше все: for (int i = 0; i < TASK_COUNT; i++) {...}
        pool.submit(new IndexTask(files));
        pool.submit(new IndexTask(files));
        pool.submit(new IndexTask(files));
    }

    // метод getWordsFrequencyMap
    // лучше возвращать копию мапы, чтобы нельзя было модифицировать из вне
    public TreeMap<String, List<Pointer>> getInvertedIndex() {
        return invertedIndex;
    }

    // Если хотим сохранность данных, то создать копию листа: new ArrayList<>(invertedIndex.get(term))
    public List<Pointer> GetRelevantDocuments(String term) {
        return invertedIndex.get(term);
    }

    public Optional<Pointer> getMostRelevantDocument(String term) {
        // Т.к. мы хотим получит максимум по числу, то можно в max передать: Comparator.comparingInt(Pointer::getCount)
        // Опять же компаратор можно инкапсулировать в класс Pointer: public static comparingByCount() {}
        return invertedIndex.get(term).stream().max(Comparator.comparing(o -> o.count));
    }

    // Не обижаем классы и создаем их как отдельные личности :)
    // Плохая практика создания иннер классов из-за этого размер класса раздувается и становится сложно читать код.
    static class Pointer {

        // Я бы добавил ещё final каждому полю, а count изменял путем создания нового обьекта 
        // или создал метод public void incrementCount() { this.count += 1 } (тогда count будет без final)
        private Integer count;
        private String filePath;

        
        // Можно использовать lombok: @AllArgsConstructor
        public Pointer(Integer count, String filePath) {
            this.count = count;
            this.filePath = filePath;
        }

        @Override
        public String toString() {
            // Работаем через шаблоны
            // return String.format("{count=%d, filePath='%s'}", this.count, this.filePath);
            return "{" + "count=" + count + ", filePath='" + filePath + '\'' + '}';
        }
    }

    // В отдельный класс. Плюс в конструктор будем передавать ссылку на invertedIndex
    class IndexTask implements Runnable {

        // Мы ожидаем, что там путь к файлам, поэтому как в классе Index назвать: files
        private final BlockingQueue<Path> queue;

        public IndexTask(BlockingQueue<Path> queue) {
            this.queue = queue;
        }

        @Override
        public void run() {
            try {
                // Переименовать в path
                Path take = queue.take();

                // Можно не создавать лишние локальные переменные и сделать рефакторинг inline variables
                List<String> strings = Files.readAllLines(take);
                
                // 1) Т.к. везде используется take.toString(), то создать локальную переменную: final String filePath = take.toString();
                // 2) Для поиска слов использовать RegExp, т.к. нужно еще учесть знаки препинания 
                // 3) Все что находится после ".forEach(word ->" вынести в отдельный метод
                strings.stream().flatMap(str -> Stream.of(str.split(" "))).forEach(word -> invertedIndex.compute(word, (k, v) -> {
                    
                    // 1) Мы уже делаем ранний возрат, блок else не нужен 
                    // 2) проверку на null лучше так записать: Objects.isNull(v)
                    // 3) Магическое число 1, вынести в константу или инкапсулирвоать в класс Pointer, создав метод: public static Pointer pointerInit(String filePath) {}
                    if (v == null) return List.of(new Pointer(1, take.toString()));
                    else {
                        ArrayList<Pointer> pointers = new ArrayList<>();

                        // Условие в nonMatch инкапсулировать в класс Pointer это его зона отвественности. Избавимся от дублирвоания кода
                        // Можно переопределить метод equals и говорить, что обьекты равны, когда равны их filePath
                        if (v.stream().noneMatch(pointer -> pointer.filePath.equals(take.toString()))) {
                            // метод pointerInit()
                            pointers.add(new Pointer(1, take.toString()));
                        }

                        v.forEach(pointer -> {
                            // Дублирование кода
                            if (pointer.filePath.equals(take.toString())) {
                                // Это метод pointer.incrementCount()
                                pointer.count = pointer.count + 1;
                            }
                        });

                        pointers.addAll(v);

                        return pointers;
                    }

                }));

                // Лучше написать несколько catch, чтобы лучше описать ошибку
            } catch (InterruptedException | IOException e) {
                // Нет описание ошибки и передачи exception в конструктор для мета-информации
                throw new RuntimeException();
            }
        }
    }
}
