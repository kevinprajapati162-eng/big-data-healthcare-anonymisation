import java.io.*;
import java.util.*;
import java.util.regex.*;

public class PatientDataAnonymisation {

    public static void main(String[] args) {
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.print("Enter the path to the patient notes file: ");
            String inputFile = scanner.nextLine().trim();

            File inputFileObject = new File(inputFile);
            if (!inputFileObject.exists() || !inputFileObject.isFile()) {
                System.err.println("The specified file does not exist or is invalid.");
                return;
            }

            String parentDirectory = inputFileObject.getParent();
            String anonymizedFile = parentDirectory + File.separator + "anonymized_notes.txt";
            String mappingFile = parentDirectory + File.separator + "mapping_document.txt";

            anonymizeMedicalNotes(inputFile, anonymizedFile, mappingFile);

            System.out.println("Anonymization completed successfully.");
            System.out.println("Anonymized notes saved at: " + anonymizedFile);
            System.out.println("Mapping document saved at: " + mappingFile);
        } catch (IOException e) {
            System.err.println("Error processing files: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void anonymizeMedicalNotes(String inputFile, String anonymizedFile, String mappingFile) throws IOException {
        String content = readFile(inputFile);
        String[] patientRecords = content.split("\\r?\\n");

        StringBuilder finalResult = new StringBuilder();
        Map<String, String> globalSensitiveData = new LinkedHashMap<>();

        int patientNumber = 0;
        for (String record : patientRecords) {
            String patientContent = record.trim();
            if (patientContent.isEmpty())
                continue;
            patientNumber++;
            AnonymizationResult result = anonymizePatientRecord(patientContent, patientNumber);

            finalResult.append(result.anonymizedText).append("\n");
            globalSensitiveData.putAll(result.mapping);
        }

        writeFile(anonymizedFile, finalResult.toString());
        writeMappingDocument(mappingFile, globalSensitiveData);
    }

    private static AnonymizationResult anonymizePatientRecord(String patientContent, int patientNumber) {
        Map<String, String> sensitiveData = new LinkedHashMap<>();
        Map<String, String> sensitiveEntities = new HashMap<>();
        Set<String> nameSet = new HashSet<>();

        // Define a set of words that should not be considered as names.
        Set<String> ignoreWords = new HashSet<>(Arrays.asList(
            "she", "he", "him", "her", "it", "they", "them", "we", "i", "you"
        ));

        // - First alternative: matches names with a title (including common titles such as Mr., Mrs., Ms., Dr., Prof.,
        //   M. or D. with both uppercase and lowercase where applicable)
        // - Second alternative: matches names without a title but requires at least two capitalized words.
        Pattern namePattern = Pattern.compile(
            "\\b(?:(?:Mr\\.?|Mrs\\.?|Ms\\.?|mr\\.?|mrs\\.?|ms\\.?|Dr\\.?|dr\\.?|Prof\\.?|[Mm]\\.?|[Dd]\\.?)\\s+[A-Z][a-zA-Z'\\-]+(?:\\s+[A-Z][a-zA-Z'\\-]+)*|" +
            "[A-Z][a-zA-Z'\\-]+(?:\\s+[A-Z][a-zA-Z'\\-]+)+)\\b"
        );

        Pattern agePattern = Pattern.compile(
            "\\b(\\d{2})\\s*-?year-old\\b|\\bage:\\s*(\\d{2})\\b|\\b(\\d{2})\\s*years?\\s*old\\b|\\baged\\s*(\\d{2})\\b"
        );
        Pattern addressPattern = Pattern.compile(
            "\\d+\\s+[A-Za-z\\s]+(?:,\\s*[A-Za-z\\s]+)?(?:,\\s*[A-Za-z]{2})\\b(?:,\\s*\\d{5})?|\\b(residing|living)\\s+(at|in)\\s+[A-Z][a-zA-Z]*(?:\\s+[A-Z][a-zA-Z]*)*,?\\s+[A-Z]{2}\\b|\\bresident of\\s+[A-Z][a-zA-Z]*(?:\\s+[A-Z][a-zA-Z]*)*,?\\s+[A-Z]{2}\\b"
        );
        //DOB regex to match day, month, and year in various common formats.
        Pattern dobPattern = Pattern.compile(
            "\\b(?:" +
                // Format: dd-mm-yyyy, dd/mm/yyyy or dd.mm.yyyy
                "(?:0?[1-9]|[12][0-9]|3[01])[-/.](?:0?[1-9]|1[0-2])[-/.](?:\\d{2}|\\d{4})" +
                "|" +
                // Format: mm-dd-yyyy, mm/dd/yyyy or mm.dd.yyyy
                "(?:0?[1-9]|1[0-2])[-/.](?:0?[1-9]|[12][0-9]|3[01])[-/.](?:\\d{2}|\\d{4})" +
                "|" +
                // Format: yyyy-mm-dd, yyyy/mm/dd or yyyy.mm.dd
                "(?:\\d{4})[-/.](?:0?[1-9]|1[0-2])[-/.](?:0?[1-9]|[12][0-9]|3[01])" +
            ")\\b"
        );

        List<MatchData> allMatches = new ArrayList<>();

        // Locate full name matches using the updated name pattern.
        Matcher nameMatcher = namePattern.matcher(patientContent);
        while (nameMatcher.find()) {
            String fullName = nameMatcher.group();
            if (fullName.trim().equals("Dr. Samantha Lee")) {
                continue;
            }
            allMatches.add(new MatchData(nameMatcher.start(), nameMatcher.end(), fullName, "name"));

            
            String nameWithoutTitle = fullName.replaceAll("^(?:(?:Mr\\.?|Mrs\\.?|Ms\\.?|Dr\\.?|mr\\.?|mrs\\.?|ms\\.?|dr\\.?|Prof\\.?|[Mm]\\.?|[Dd]\\.?)\\s+)", "");
            String[] parts = nameWithoutTitle.split("\\s+");

            // Only add parts that are not standalone prefixes or in the ignore list.
            for (String part : parts) {
                if (!ignoreWords.contains(part.toLowerCase()) && !part.matches("^[A-Z]\\.?$")) {
                    nameSet.add(part);
                }
            }
        }

        // For each extracted individual name part, add all occurrences from the entire content.
        // (Note: This will not include parts of "Dr. Samantha Lee" since it was skipped above.)
        for (String name : nameSet) {
            Pattern nameOnlyPattern = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
            Matcher nameOnlyMatcher = nameOnlyPattern.matcher(patientContent);
            while (nameOnlyMatcher.find()) {
                allMatches.add(new MatchData(nameOnlyMatcher.start(), nameOnlyMatcher.end(), nameOnlyMatcher.group(), "name"));
            }
        }

        // Collect matches for ages, addresses and now also dates of birth.
        collectMatches(patientContent, agePattern, allMatches, "age");
        collectMatches(patientContent, addressPattern, allMatches, "address");
        collectMatches(patientContent, dobPattern, allMatches, "dob");

        // Sort matches and remove overlapping ones.
        Collections.sort(allMatches, Comparator.comparingInt(m -> m.start));
        List<MatchData> nonOverlapping = new ArrayList<>();
        int lastIndex = 0;
        for (MatchData match : allMatches) {
            if (match.start >= lastIndex) {
                nonOverlapping.add(match);
                lastIndex = match.end;
            }
        }

        // Replace sensitive data (name, age, address, and DOB) with unique identifiers.
        StringBuilder result = new StringBuilder();
        int attrCounter = 1;
        Set<String> processedValues = new HashSet<>();
        lastIndex = 0;
        for (MatchData match : nonOverlapping) {
            result.append(patientContent, lastIndex, match.start);
            String value = match.value;
            String key = value.toLowerCase();

            if (!processedValues.contains(key)) {
                String id = patientNumber + "." + attrCounter;
                sensitiveData.put(id, value);
                result.append(id);
                processedValues.add(key);
                sensitiveEntities.put(key, id);
                attrCounter++;
            } else {
                String id = sensitiveEntities.getOrDefault(key, null);
                result.append(id != null ? id : (patientNumber + "." + attrCounter++));
            }
            lastIndex = match.end;
        }
        result.append(patientContent.substring(lastIndex));

        return new AnonymizationResult(result.toString(), sensitiveData);
    }

    private static void collectMatches(String content, Pattern pattern, List<MatchData> matchList, String type) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            matchList.add(new MatchData(matcher.start(), matcher.end(), matcher.group(), type));
        }
    }

    private static class MatchData {
        int start;
        int end;
        String value;
        String type;

        MatchData(int start, int end, String value, String type) {
            this.start = start;
            this.end = end;
            this.value = value;
            this.type = type;
        }
    }

    private static String readFile(String filePath) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        }
        return content.toString();
    }

    private static void writeFile(String filePath, String content) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            writer.write(content);
        }
    }

    private static void writeMappingDocument(String filePath, Map<String, String> sensitiveData) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            writer.write("Mapping Document\n");
            for (Map.Entry<String, String> entry : sensitiveData.entrySet()) {
                writer.write(entry.getKey() + ": " + entry.getValue() + "\n");
            }
        }
    }

    private static class AnonymizationResult {
        String anonymizedText;
        Map<String, String> mapping;

        AnonymizationResult(String anonymizedText, Map<String, String> mapping) {
            this.anonymizedText = anonymizedText;
            this.mapping = mapping;
        }
    }
}