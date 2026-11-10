#!/usr/bin/env groovy
// Run with compiled ADK classes and their deployed dependency classpath.
// Actual SDK Event/Content/Part objects only; no provider, network or database.
import com.google.adk.events.Event
import com.google.genai.types.Content
import com.google.genai.types.FunctionCall
import com.google.genai.types.GenerateContentResponseUsageMetadata
import com.google.genai.types.Part
import java.nio.charset.StandardCharsets
import java.util.Arrays

Class manager = Class.forName('org.moqui.adk.AdkManager')
def wire = { Event event -> manager.eventToMap(event) as Map }
def makeEvent = { String id, Content content ->
    Event.builder().id(id).invocationId('invocation-test').author('nasqo-test')
            .content(content).build()
}
String fence = '\u0060' * 3
String expected = '  مرحباً 👋\n  كيف أساعدك؟\n\n' + fence +
        'growerp-action\n{"action":"navigate","widget":"CustomerList","label":"العملاء"}\n' +
        fence + '\n  '
List<String> segments = [
        '  مرحباً 👋', '\n  ', 'كيف أساعدك؟',
        '\n\n' + fence + 'growerp-action\n{"action":"navigate",',
        '"widget":"CustomerList","label":"العملاء"}\n', fence + '\n  '
]
Part function = Part.builder().functionCall(
        FunctionCall.builder().name('test_only_function').args([value: 'test']).build()).build()
Part thought = Part.builder().text('private reasoning must not reach the wire').thought(true).build()
List<Part> multipart = [
        Part.fromText(segments[0]), thought, Part.fromText(segments[1]),
        function, Part.builder().text(segments[2]).thought(false).build(),
        Part.fromText(segments[3]), Part.fromText(segments[4]), Part.fromText(segments[5])
]
def usage = GenerateContentResponseUsageMetadata.builder()
        .promptTokenCount(17).candidatesTokenCount(29).totalTokenCount(46).build()
Event event = Event.builder().id('event-multipart').invocationId('invocation-test')
        .author('nasqo-test').content(Content.builder().role('model').parts(multipart).build())
        .partial(false).usageMetadata(usage).build()
String originalJson = event.toJson()
Map mapped = wire(event)

assert mapped.id == 'event-multipart'
assert mapped.invocationId == 'invocation-test'
assert mapped.author == 'nasqo-test'
assert mapped.partial == false
assert mapped.content.role == 'model'
assert mapped.content.parts == [[text: expected]]:
        'Visible SDK parts must form exactly one wire message in their original order'
assert Arrays.equals((mapped.content.parts[0].text as String).getBytes(StandardCharsets.UTF_8),
        expected.getBytes(StandardCharsets.UTF_8)):
        'Joining must preserve whitespace, Unicode and action-block bytes'
assert mapped.usageMetadata == [promptTokenCount: 17, candidatesTokenCount: 29, totalTokenCount: 46]
assert !mapped.content.parts[0].text.contains('private reasoning')
assert !mapped.content.parts[0].text.contains('test_only_function')

// Mapping must not rewrite stored SDK event content or its hidden/function parts.
assert event.toJson() == originalJson
assert event.content().get().parts().get().size() == multipart.size()
assert event.content().get().parts().get()[1].thought().get()
assert event.content().get().parts().get()[3].functionCall().get().name().get() == 'test_only_function'
assert wire(event) == mapped: 'Mapping should be repeatable'

Event single = makeEvent('event-single',
        Content.builder().role('model').parts([Part.fromText('  نص واحد\n')]).build())
Map singleWire = wire(single)
assert singleWire.content.parts == [[text: '  نص واحد\n']]
assert !singleWire.containsKey('partial')
assert !singleWire.containsKey('usageMetadata')

Event emptyText = makeEvent('event-empty-text',
        Content.builder().parts([Part.fromText('')]).build())
assert wire(emptyText).content == [role: '', parts: [[text: '']]]:
        'A present empty text field is still a text part'

Event noParts = makeEvent('event-no-parts', Content.builder().role('model').build())
assert wire(noParts).content == [role: 'model', parts: []]
Event emptyParts = makeEvent('event-empty-parts',
        Content.builder().role('model').parts([] as List<Part>).build())
assert wire(emptyParts).content == [role: 'model', parts: []]
Event nonText = makeEvent('event-non-text',
        Content.builder().role('model').parts([function, thought]).build())
String nonTextJson = nonText.toJson()
assert wire(nonText).content == [role: 'model', parts: []]:
        'Function-only or thought-only events must not create blank wire bubbles'
assert nonText.toJson() == nonTextJson

Event noContent = Event.builder().id('event-no-content').invocationId('invocation-test')
        .author('nasqo-test').partial(true).build()
assert wire(noContent) == [id: 'event-no-content', invocationId: 'invocation-test',
                          author: 'nasqo-test', partial: true]

// Collapse within an event only. Streaming event boundaries and partial flags survive.
Event first = Event.builder().id('stream-1').invocationId('stream-test').author('nasqo-test')
        .content(Content.builder().role('model').parts([Part.fromText('أ'), Part.fromText('ب')]).build())
        .partial(true).build()
Event second = Event.builder().id('stream-2').invocationId('stream-test').author('nasqo-test')
        .content(Content.builder().role('model').parts([Part.fromText('ج'), Part.fromText('د')]).build())
        .partial(false).build()
assert wire(first).content.parts == [[text: 'أب']]
assert wire(first).partial == true && wire(first).id == 'stream-1'
assert wire(second).content.parts == [[text: 'جد']]
assert wire(second).partial == false && wire(second).id == 'stream-2'

println 'ADK_EVENT_TEXT_WIRE_REGRESSION_PASS'
